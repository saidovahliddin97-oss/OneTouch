import AVFoundation
import CoreMedia
import ScreenCaptureKit
import VideoToolbox

/// Something that produces frames: the real screen, or a synthetic pattern
/// for automated tests (CI machines cannot grant Screen Recording).
protocol FrameSource: AnyObject {
    var onFrame: ((CVPixelBuffer, CMTime) -> Void)? { get set }
    /// Calls back with the pixel size that frames will have.
    func start(maxWidth: Int, completion: @escaping (Result<(Int, Int), Error>) -> Void)
    func stop()
}

struct SimpleError: LocalizedError {
    let errorDescription: String?
    init(_ s: String) { errorDescription = s }
}

/// The main display via ScreenCaptureKit. It only delivers frames when the
/// screen changes, so a static desktop costs (almost) nothing.
final class ScreenSource: NSObject, FrameSource, SCStreamOutput, SCStreamDelegate {
    var onFrame: ((CVPixelBuffer, CMTime) -> Void)?
    private var stream: SCStream?
    private let queue = DispatchQueue(label: "onetouch.capture")

    func start(maxWidth: Int, completion: @escaping (Result<(Int, Int), Error>) -> Void) {
        SCShareableContent.getExcludingDesktopWindows(false, onScreenWindowsOnly: true) { [weak self] content, error in
            guard let self else { return }
            guard let display = content?.displays.first(where: { $0.displayID == CGMainDisplayID() }) ?? content?.displays.first else {
                completion(.failure(error ?? SimpleError("Нет доступа к экрану")))
                return
            }
            let mode = CGDisplayCopyDisplayMode(display.displayID)
            let pixelW = max(display.width, mode?.pixelWidth ?? display.width)
            let w = min(maxWidth, pixelW) & ~1
            let h = Int(Double(w) * Double(display.height) / Double(display.width)) & ~1
            let cfg = SCStreamConfiguration()
            cfg.width = w
            cfg.height = h
            cfg.minimumFrameInterval = CMTime(value: 1, timescale: 30)
            cfg.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            cfg.showsCursor = true
            cfg.queueDepth = 5
            let filter = SCContentFilter(display: display, excludingWindows: [])
            let s = SCStream(filter: filter, configuration: cfg, delegate: self)
            do {
                try s.addStreamOutput(self, type: .screen, sampleHandlerQueue: self.queue)
            } catch {
                completion(.failure(error))
                return
            }
            s.startCapture { err in
                if let err { completion(.failure(err)) } else { completion(.success((w, h))) }
            }
            self.stream = s
        }
    }

    func stop() {
        stream?.stopCapture { _ in }
        stream = nil
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sb: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sb.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let raw = attachments.first?[.status] as? Int, SCFrameStatus(rawValue: raw) == .complete,
              let pb = CMSampleBufferGetImageBuffer(sb) else { return }
        onFrame?(pb, CMSampleBufferGetPresentationTimeStamp(sb))
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        logLine("capture stopped: \(error.localizedDescription)")
    }
}

/// Moving test pattern (ONETOUCH_FAKE_SCREEN=1).
final class SyntheticSource: FrameSource {
    var onFrame: ((CVPixelBuffer, CMTime) -> Void)?
    private var timer: DispatchSourceTimer?
    private var n: Int64 = 0

    func start(maxWidth: Int, completion: @escaping (Result<(Int, Int), Error>) -> Void) {
        let w = 640, h = 400
        let t = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "onetouch.synthetic"))
        t.schedule(deadline: .now(), repeating: .milliseconds(66))
        t.setEventHandler { [weak self] in
            guard let self, let pb = Self.makeFrame(w: w, h: h, phase: Int(self.n)) else { return }
            self.onFrame?(pb, CMTime(value: self.n, timescale: 15))
            self.n += 1
        }
        t.resume()
        timer = t
        completion(.success((w, h)))
    }

    func stop() {
        timer?.cancel()
        timer = nil
    }

    private static func makeFrame(w: Int, h: Int, phase: Int) -> CVPixelBuffer? {
        var pb: CVPixelBuffer?
        let attrs = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        guard CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange, attrs, &pb) == kCVReturnSuccess,
              let pb else { return nil }
        CVPixelBufferLockBaseAddress(pb, [])
        if let y = CVPixelBufferGetBaseAddressOfPlane(pb, 0) {
            let stride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
            for row in 0..<h {
                memset(y + row * stride, Int32(16 + (row + phase * 4) % 220), w)
            }
        }
        if let uv = CVPixelBufferGetBaseAddressOfPlane(pb, 1) {
            memset(uv, 128, CVPixelBufferGetBytesPerRowOfPlane(pb, 1) * h / 2)
        }
        CVPixelBufferUnlockBaseAddress(pb, [])
        return pb
    }
}

/// Hardware H.264 encoder (VideoToolbox) producing Annex-B for MediaCodec.
final class H264Encoder {
    /// (SPS+PPS Annex-B, only on key frames; frame Annex-B; is key frame)
    var onOutput: ((Data?, Data, Bool) -> Void)?
    private var session: VTCompressionSession?
    private var forceKey = true
    private let lock = NSLock()

    func setup(width: Int, height: Int, bitrate: Int = 8_000_000) throws {
        var s: VTCompressionSession?
        let spec = [kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true] as CFDictionary
        var st = VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height), codecType: kCMVideoCodecType_H264,
                                            encoderSpecification: spec, imageBufferAttributes: nil, compressedDataAllocator: nil,
                                            outputCallback: nil, refcon: nil, compressionSessionOut: &s)
        if st != noErr { // low-latency mode is not available everywhere
            st = VTCompressionSessionCreate(allocator: nil, width: Int32(width), height: Int32(height), codecType: kCMVideoCodecType_H264,
                                            encoderSpecification: nil, imageBufferAttributes: nil, compressedDataAllocator: nil,
                                            outputCallback: nil, refcon: nil, compressionSessionOut: &s)
        }
        guard st == noErr, let s else { throw SimpleError("Кодер H.264 недоступен (\(st))") }
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ProfileLevel, value: kVTProfileLevel_H264_Main_AutoLevel)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_AverageBitRate, value: bitrate as CFNumber)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: 240 as CFNumber)
        VTSessionSetProperty(s, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: 30 as CFNumber)
        VTCompressionSessionPrepareToEncodeFrames(s)
        session = s
    }

    func requestKeyframe() {
        lock.lock(); forceKey = true; lock.unlock()
    }

    func encode(_ pb: CVPixelBuffer, pts: CMTime) {
        guard let session else { return }
        lock.lock()
        let key = forceKey
        forceKey = false
        lock.unlock()
        let props = key ? [kVTEncodeFrameOptionKey_ForceKeyFrame: kCFBooleanTrue] as CFDictionary : nil
        VTCompressionSessionEncodeFrame(session, imageBuffer: pb, presentationTimeStamp: pts, duration: .invalid,
                                        frameProperties: props, infoFlagsOut: nil) { [weak self] status, _, sb in
            guard status == noErr, let sb, let self else { return }
            let (config, frame, isKey) = Self.annexB(sb)
            if !frame.isEmpty { self.onOutput?(config, frame, isKey) }
        }
    }

    func invalidate() {
        if let session { VTCompressionSessionInvalidate(session) }
        session = nil
    }

    private static let startCode = Data([0, 0, 0, 1])

    /// AVCC (length-prefixed) sample → Annex-B (start codes), plus SPS/PPS on key frames.
    static func annexB(_ sb: CMSampleBuffer) -> (Data?, Data, Bool) {
        let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false) as? [[CFString: Any]]
        let notSync = attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false
        let isKey = !notSync
        var config: Data?
        if isKey, let fd = CMSampleBufferGetFormatDescription(sb) {
            var count = 0
            CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: 0, parameterSetPointerOut: nil,
                                                               parameterSetSizeOut: nil, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
            var c = Data()
            for i in 0..<count {
                var ptr: UnsafePointer<UInt8>?
                var size = 0
                if CMVideoFormatDescriptionGetH264ParameterSetAtIndex(fd, parameterSetIndex: i, parameterSetPointerOut: &ptr,
                                                                      parameterSetSizeOut: &size, parameterSetCountOut: nil,
                                                                      nalUnitHeaderLengthOut: nil) == noErr, let ptr {
                    c.append(startCode)
                    c.append(ptr, count: size)
                }
            }
            config = c
        }
        guard let bb = CMSampleBufferGetDataBuffer(sb) else { return (config, Data(), isKey) }
        let total = CMBlockBufferGetDataLength(bb)
        var avcc = Data(count: total)
        let ok = avcc.withUnsafeMutableBytes { raw in
            CMBlockBufferCopyDataBytes(bb, atOffset: 0, dataLength: total, destination: raw.baseAddress!) == noErr
        }
        guard ok else { return (config, Data(), isKey) }
        var out = Data(capacity: total + 16)
        var off = 0
        while off + 4 <= total {
            let len = Int(avcc[off]) << 24 | Int(avcc[off + 1]) << 16 | Int(avcc[off + 2]) << 8 | Int(avcc[off + 3])
            off += 4
            guard len > 0, off + len <= total else { break }
            out.append(startCode)
            out.append(avcc.subdata(in: off..<(off + len)))
            off += len
        }
        return (config, out, isKey)
    }
}

/// Splits an Annex-B byte stream into NAL units (without start codes).
func splitNALs(_ d: Data) -> [Data] {
    var nals: [Data] = []
    let bytes = [UInt8](d)
    var i = 0
    var start = -1
    while i + 3 <= bytes.count {
        let three = bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1
        if three {
            if start >= 0 {
                var end = i
                if end > start, bytes[end - 1] == 0 { end -= 1 } // 4-byte start code
                if end > start { nals.append(Data(bytes[start..<end])) }
            }
            i += 3
            start = i
        } else {
            i += 1
        }
    }
    if start >= 0, start < bytes.count { nals.append(Data(bytes[start...])) }
    return nals
}
