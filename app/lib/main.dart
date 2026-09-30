import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:nsd/nsd.dart' as nsd;

const serviceType = '_onetouch._tcp';

void main() => runApp(const OneTouchApp());

class OneTouchApp extends StatelessWidget {
  const OneTouchApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'OneTouch',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF4F8CFF), brightness: Brightness.dark),
          useMaterial3: true,
        ),
        home: const HomePage(),
      );
}

/// A OneTouch node found via mDNS. The TXT record carries the TLS
/// certificate fingerprint, which we pin on every connection.
class Peer {
  Peer({required this.id, required this.name, required this.os, required this.fp, required this.host, required this.port});
  final String id, name, os, fp, host;
  final int port;

  static Peer? fromService(nsd.Service s) {
    final txt = <String, String>{};
    s.txt?.forEach((k, v) => txt[k] = v == null ? '' : utf8.decode(v, allowMalformed: true));
    final addr = s.addresses?.where((a) => a.type == InternetAddressType.IPv4).firstOrNull ?? s.addresses?.firstOrNull;
    final host = addr?.address ?? s.host;
    if (txt['id'] == null || host == null || s.port == null) return null;
    return Peer(id: txt['id']!, name: txt['name'] ?? s.name ?? 'device', os: txt['os'] ?? '', fp: txt['fp'] ?? '', host: host, port: s.port!);
  }
}

/// Minimal client for the OneTouch v1 protocol (see core/internal/transfer/server.go).
class OneTouchClient {
  OneTouchClient(this.peer) {
    _http = HttpClient()
      ..connectionTimeout = const Duration(seconds: 5)
      ..idleTimeout = const Duration(seconds: 15)
      // Self-signed cert: trust it only if its SHA-256 matches the mDNS-advertised fingerprint.
      ..badCertificateCallback = (cert, host, port) => peer.fp.isNotEmpty && sha256.convert(cert.der).toString() == peer.fp;
  }
  final Peer peer;
  late final HttpClient _http;

  Uri _uri(String path, [Map<String, String>? q]) =>
      Uri(scheme: 'https', host: peer.host, port: peer.port, path: path, queryParameters: q);

  Future<void> send(Item it, String mode, void Function(double) onProgress) async {
    final total = await it.file.xFile.length();
    final req = await _http.putUrl(_uri('/v1/files', {'name': it.file.name, 'mode': mode, 'from': deviceName}));
    req.contentLength = total;
    req.headers.contentType = ContentType.binary;
    var sent = 0;
    await req.addStream(it.file.xFile.openRead().map((chunk) {
      sent += chunk.length;
      onProgress(total == 0 ? 1 : sent / total);
      return chunk;
    }));
    final resp = await req.close();
    final body = await resp.transform(utf8.decoder).join();
    if (resp.statusCode != 200) throw HttpException('${resp.statusCode}: $body');
  }

  void close() => _http.close(force: true);
}

String get deviceName => Platform.isAndroid ? 'Android' : Platform.localHostname;

enum Status { idle, sending, clipped, saved, failed }

class Item {
  Item(this.file);
  final PlatformFile file;
  Status status = Status.idle;
  double progress = 0;
  String? error;
  bool get isImage => const ['jpg', 'jpeg', 'png', 'gif', 'webp', 'heic', 'heif', 'bmp'].contains(file.extension?.toLowerCase());
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  nsd.Discovery? _discovery;
  final Map<String, Peer> _peers = {};
  String? _selectedId;
  final List<Item> _items = [];

  Peer? get _target => _peers[_selectedId] ?? (_peers.length == 1 ? _peers.values.first : null);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _startDiscovery();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _stopDiscovery();
    super.dispose();
  }

  // Energy: browse only while the app is visible; nothing runs in the background.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _startDiscovery();
    } else if (state == AppLifecycleState.paused) {
      _stopDiscovery();
    }
  }

  Future<void> _startDiscovery() async {
    if (_discovery != null) return;
    try {
      final d = await nsd.startDiscovery(serviceType, ipLookupType: nsd.IpLookupType.v4);
      d.addServiceListener((s, status) {
        final p = Peer.fromService(s);
        setState(() {
          if (status == nsd.ServiceStatus.found && p != null) {
            _peers[p.id] = p;
          } else if (status == nsd.ServiceStatus.lost) {
            _peers.removeWhere((_, v) => v.name == s.name || (p != null && v.id == p.id));
          }
        });
      });
      _discovery = d;
    } catch (e) {
      _toast('mDNS: $e');
    }
  }

  Future<void> _stopDiscovery() async {
    final d = _discovery;
    _discovery = null;
    if (d != null) await nsd.stopDiscovery(d).catchError((_) {});
  }

  Future<void> _pick() async {
    final files = await FilePicker.pickFiles(type: FileType.media);
    if (files.isEmpty) return;
    setState(() => _items.insertAll(0, files.map(Item.new)));
  }

  Future<void> _send(Item it, String mode) async {
    final peer = _target;
    if (peer == null) {
      _toast(_peers.isEmpty ? 'Нет устройств: запустите `onetouch serve` на компьютере' : 'Выберите устройство сверху');
      return;
    }
    if (it.status == Status.sending) return;
    HapticFeedback.mediumImpact();
    setState(() {
      it.status = Status.sending;
      it.progress = 0;
    });
    final c = OneTouchClient(peer);
    try {
      await c.send(it, mode, (p) => setState(() => it.progress = p));
      HapticFeedback.lightImpact();
      setState(() => it.status = mode == 'clip' ? Status.clipped : Status.saved);
    } catch (e) {
      setState(() {
        it.status = Status.failed;
        it.error = '$e';
      });
      _toast('Ошибка: $e');
    } finally {
      c.close();
    }
  }

  void _toast(String m) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(m)));
  }

  @override
  Widget build(BuildContext context) {
    final peers = _peers.values.toList()..sort((a, b) => a.name.compareTo(b.name));
    return Scaffold(
      appBar: AppBar(title: const Text('OneTouch')),
      body: Column(children: [
        SizedBox(
          height: 56,
          child: peers.isEmpty
              ? const Center(child: Text('Ищу устройства в Wi‑Fi…', style: TextStyle(color: Colors.white54)))
              : ListView(
                  scrollDirection: Axis.horizontal,
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  children: [
                    for (final p in peers)
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: ChoiceChip(
                          avatar: Icon(p.os == 'windows' ? Icons.desktop_windows : Icons.laptop_mac, size: 18),
                          label: Text(p.name),
                          selected: _target?.id == p.id,
                          onSelected: (_) => setState(() => _selectedId = p.id),
                        ),
                      ),
                  ],
                ),
        ),
        const Padding(
          padding: EdgeInsets.fromLTRB(16, 0, 16, 8),
          child: Text('🤏 Щипок по фото — в буфер компьютера (там: хоткей «вставить»).  ↑ — сразу на рабочий стол.',
              style: TextStyle(color: Colors.white60, fontSize: 13)),
        ),
        Expanded(
          child: _items.isEmpty
              ? Center(child: FilledButton.icon(onPressed: _pick, icon: const Icon(Icons.photo_library), label: const Text('Выбрать фото / видео')))
              : GridView.builder(
                  padding: const EdgeInsets.all(12),
                  gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(maxCrossAxisExtent: 180, mainAxisSpacing: 10, crossAxisSpacing: 10),
                  itemCount: _items.length,
                  itemBuilder: (_, i) => PinchTile(
                    item: _items[i],
                    onPinch: () => _send(_items[i], 'clip'),
                    onSend: () => _send(_items[i], 'save'),
                  ),
                ),
        ),
      ]),
      floatingActionButton: _items.isEmpty ? null : FloatingActionButton(onPressed: _pick, child: const Icon(Icons.add_photo_alternate)),
    );
  }
}

/// A media tile that shrinks under a two-finger pinch-in and fires [onPinch]
/// once the fingers have closed past 60% of the starting distance.
class PinchTile extends StatefulWidget {
  const PinchTile({super.key, required this.item, required this.onPinch, required this.onSend});
  final Item item;
  final VoidCallback onPinch, onSend;
  @override
  State<PinchTile> createState() => _PinchTileState();
}

class _PinchTileState extends State<PinchTile> {
  double _scale = 1;
  bool _armed = false;

  @override
  Widget build(BuildContext context) {
    final it = widget.item;
    final (label, color) = switch (it.status) {
      Status.idle => ('', Colors.transparent),
      Status.sending => ('${(it.progress * 100).round()}%', Colors.black54),
      Status.clipped => ('📋 в буфере', Colors.green),
      Status.saved => ('✓ на столе', Colors.green),
      Status.failed => ('✕ ошибка', Colors.red),
    };
    return GestureDetector(
      onScaleStart: (_) => _armed = false,
      onScaleUpdate: (d) {
        if (d.pointerCount < 2) return;
        final s = d.scale.clamp(0.35, 1.0);
        final armed = s < 0.6;
        if (armed && !_armed) HapticFeedback.selectionClick();
        setState(() {
          _scale = s;
          _armed = armed;
        });
      },
      onScaleEnd: (_) {
        if (_armed) widget.onPinch();
        setState(() {
          _scale = 1;
          _armed = false;
        });
      },
      child: AnimatedScale(
        scale: _scale,
        duration: const Duration(milliseconds: 80),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(14),
          child: Stack(fit: StackFit.expand, children: [
            Container(
              decoration: BoxDecoration(
                color: const Color(0xFF151923),
                border: Border.all(color: _armed ? Theme.of(context).colorScheme.primary : Colors.white12, width: _armed ? 3 : 1),
                borderRadius: BorderRadius.circular(14),
              ),
              child: it.isImage && it.file.path != null
                  ? Image.file(File(it.file.path!), fit: BoxFit.cover, cacheWidth: 360)
                  : Center(child: Padding(padding: const EdgeInsets.all(8), child: Text('🎞\n${it.file.name}', textAlign: TextAlign.center, style: const TextStyle(fontSize: 12)))),
            ),
            if (label.isNotEmpty)
              Positioned(
                top: 6,
                left: 6,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(99)),
                  child: Text(label, style: const TextStyle(fontSize: 12, color: Colors.white)),
                ),
              ),
            if (it.status == Status.sending)
              Align(alignment: Alignment.bottomCenter, child: LinearProgressIndicator(value: it.progress, minHeight: 4)),
            Positioned(
              right: 6,
              bottom: 8,
              child: IconButton.filledTonal(onPressed: widget.onSend, icon: const Icon(Icons.upload, size: 18), visualDensity: VisualDensity.compact),
            ),
          ]),
        ),
      ),
    );
  }
}
