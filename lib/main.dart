import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const BluetoothRemoteApp());

class BluetoothRemoteApp extends StatelessWidget {
  const BluetoothRemoteApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'BD Bluetooth TV Remote',
    theme: ThemeData(
      brightness: Brightness.dark,
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF31D6A0), brightness: Brightness.dark),
      scaffoldBackgroundColor: const Color(0xFF07110F),
      useMaterial3: true,
    ),
    home: const RemoteHome(),
  );
}

class RemoteHome extends StatefulWidget {
  const RemoteHome({super.key});
  @override
  State<RemoteHome> createState() => _RemoteHomeState();
}

class _RemoteHomeState extends State<RemoteHome> {
  static const _bridge = MethodChannel('bd_remote/hid');
  String _status = 'Not connected';
  bool _ready = false;
  bool _busy = false;
  List<Map<String, dynamic>> _tvs = [];

  Future<void> _connect() async {
    setState(() { _busy = true; _status = 'Searching for Android TVs on this Wi-Fi…'; _tvs = []; });
    try {
      final result = await _bridge.invokeMethod<List<dynamic>>('discoverTvs');
      if (!mounted) return;
      final devices = (result ?? []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
      setState(() {
        _tvs = devices;
        _status = devices.isEmpty
            ? 'No Android TV found. Make sure TV and phone use the same Wi-Fi.'
            : 'Found ${devices.length} Android TV device(s). Select your TV.';
        _ready = false;
      });
    } on PlatformException catch (e) {
      if (!mounted) return;
      setState(() { _status = e.message ?? 'TV discovery failed'; _ready = false; });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _key(int code) async {
    try { await _bridge.invokeMethod('sendKey', {'key': code}); }
    on PlatformException catch (e) { if (mounted) setState(() => _status = e.message ?? 'Not paired with TV'); }
  }

  Future<void> _consumer(int usage) async {
    try { await _bridge.invokeMethod('sendConsumer', {'usage': usage}); }
    on PlatformException catch (e) { if (mounted) setState(() => _status = e.message ?? 'Not paired with TV'); }
  }

  Widget _keyButton(String label, {int? key, int? usage, IconData? icon, Color? color, double height = 58}) {
    return SizedBox(height: height, child: FilledButton(
      style: FilledButton.styleFrom(backgroundColor: color ?? const Color(0xFF172923), foregroundColor: Colors.white, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(17))),
      onPressed: () { if (key != null) _key(key); if (usage != null) _consumer(usage); },
      child: icon != null ? Icon(icon, size: 25) : Text(label, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Row(children: [Icon(Icons.settings_remote, color: Color(0xFF31D6A0)), SizedBox(width: 10), Text('BD TV Remote', style: TextStyle(fontWeight: FontWeight.bold))]),
        actions: [IconButton(onPressed: _connect, tooltip: 'Find Android TV', icon: const Icon(Icons.wifi_find))],
      ),
      body: SafeArea(child: ListView(padding: const EdgeInsets.fromLTRB(20, 12, 20, 28), children: [
        Container(
          padding: const EdgeInsets.all(18),
          decoration: BoxDecoration(gradient: const LinearGradient(colors: [Color(0xFF153D30), Color(0xFF10231E)]), borderRadius: BorderRadius.circular(22), border: Border.all(color: const Color(0xFF28614D))),
          child: Row(children: [
            Container(width: 48, height: 48, decoration: BoxDecoration(color: const Color(0xFF31D6A0).withOpacity(.14), borderRadius: BorderRadius.circular(15)), child: const Icon(Icons.bluetooth, color: Color(0xFF31D6A0), size: 28)),
            const SizedBox(width: 13), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(_status, style: const TextStyle(fontWeight: FontWeight.bold)),
              const SizedBox(height: 4), const Text('Find your Android TV on the same Wi-Fi network, then select it to pair.', style: TextStyle(color: Colors.white70, fontSize: 12)),
            ])),
            if (_busy) const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
          ]),
        ),
        const SizedBox(height: 15),
        SizedBox(height: 50, child: FilledButton.icon(onPressed: _busy ? null : _connect, icon: const Icon(Icons.wifi_find), label: const Text('FIND ANDROID TV'))),
        if (_tvs.isNotEmpty) ...[
          const SizedBox(height: 16),
          const Text('ANDROID TV DEVICES', style: TextStyle(color: Color(0xFF8BA69A), letterSpacing: 1.5, fontSize: 11, fontWeight: FontWeight.bold)),
          const SizedBox(height: 8),
          ..._tvs.map((tv) => Card(
            color: const Color(0xFF172923),
            child: ListTile(
              leading: const Icon(Icons.tv, color: Color(0xFF31D6A0)),
              title: Text(tv['name']?.toString() ?? 'Android TV'),
              subtitle: Text(tv['host']?.toString() ?? ''),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => setState(() => _status = 'TV found: ${tv['name']}. Secure pairing is the next connection step.'),
            ),
          )),
        ],
        const SizedBox(height: 24),
        const Text('NAVIGATION', style: TextStyle(color: Color(0xFF8BA69A), letterSpacing: 1.5, fontSize: 11, fontWeight: FontWeight.bold)),
        const SizedBox(height: 12),
        Center(child: SizedBox(width: 220, child: _keyButton('▲', key: 0x52, height: 58))),
        Center(child: SizedBox(width: 220, child: Row(children: [
          Expanded(child: _keyButton('◀', key: 0x50)), const SizedBox(width: 9),
          Expanded(child: _keyButton('OK', key: 0x28, color: const Color(0xFF147B5A))), const SizedBox(width: 9),
          Expanded(child: _keyButton('▶', key: 0x4F)),
        ]))),
        Center(child: SizedBox(width: 220, child: _keyButton('▼', key: 0x51))),
        const SizedBox(height: 22),
        const Text('CONTROL', style: TextStyle(color: Color(0xFF8BA69A), letterSpacing: 1.5, fontSize: 11, fontWeight: FontWeight.bold)),
        const SizedBox(height: 12),
        Row(children: [
          Expanded(child: _keyButton('Back', key: 0x29, icon: Icons.arrow_back)),
          const SizedBox(width: 10), Expanded(child: _keyButton('Home', key: 0x4A, icon: Icons.home)),
          const SizedBox(width: 10), Expanded(child: _keyButton('Menu', key: 0x76, icon: Icons.menu)),
        ]),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(child: _keyButton('Vol −', usage: 0x00EA, icon: Icons.volume_down)),
          const SizedBox(width: 10), Expanded(child: _keyButton('Mute', usage: 0x00E2, icon: Icons.volume_off)),
          const SizedBox(width: 10), Expanded(child: _keyButton('Vol +', usage: 0x00E9, icon: Icons.volume_up)),
        ]),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(child: _keyButton('Previous', usage: 0x00B6, icon: Icons.skip_previous)),
          const SizedBox(width: 10), Expanded(child: _keyButton('Play / Pause', usage: 0x00CD, icon: Icons.play_arrow)),
          const SizedBox(width: 10), Expanded(child: _keyButton('Next', usage: 0x00B5, icon: Icons.skip_next)),
        ]),
        const SizedBox(height: 22),
        Container(padding: const EdgeInsets.all(15), decoration: BoxDecoration(color: const Color(0xFF111E1A), borderRadius: BorderRadius.circular(17)), child: const Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('PAIRING GUIDE', style: TextStyle(fontWeight: FontWeight.bold, color: Color(0xFF31D6A0))),
          SizedBox(height: 8),
          Text('1. Connect your phone and Android TV to the same Wi-Fi network.\n2. Tap FIND ANDROID TV.\n3. Select your TV from the discovered device list.\n4. Complete the secure pairing code shown by the TV.', style: TextStyle(height: 1.6, color: Colors.white70, fontSize: 13)),
          SizedBox(height: 8),
          Text('Note: Your phone must support Bluetooth HID Device mode and the TV must accept Bluetooth keyboards. Power and some TV-specific keys may not be supported.', style: TextStyle(color: Colors.white54, fontSize: 11, height: 1.5)),
        ])),
      ])),
    );
  }
}
