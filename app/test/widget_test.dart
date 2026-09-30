import 'package:flutter_test/flutter_test.dart';
import 'package:nsd/nsd.dart' as nsd;
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:onetouch/main.dart';

void main() {
  test('Peer parses mDNS TXT record', () {
    final s = nsd.Service(
      name: 'MacBook-abc123',
      port: 47470,
      addresses: [InternetAddress('192.168.1.20')],
      txt: {
        'id': Uint8List.fromList(utf8.encode('abc123')),
        'name': Uint8List.fromList(utf8.encode('MacBook')),
        'os': Uint8List.fromList(utf8.encode('darwin')),
        'fp': Uint8List.fromList(utf8.encode('ff00')),
      },
    );
    final p = Peer.fromService(s)!;
    expect(p.name, 'MacBook');
    expect(p.host, '192.168.1.20');
    expect(p.fp, 'ff00');
  });
}
