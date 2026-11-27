import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const BluetoothRemoteApp());

class BluetoothRemoteApp extends StatelessWidget {
  const BluetoothRemoteApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Bluetooth Remote',
    theme: ThemeData(
      brightness: Brightness.dark,
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF269FE5), brightness: Brightness.dark),
      scaffoldBackgroundColor: const Color(0xFF101010),
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
  String _status = 'Bluetooth remote is not started';
  bool _ready = false;
  bool _busy = false;
  bool _connected = false;
  String _connectedName = '';
  List<Map<String, dynamic>> _pairedDevices = [];
  Timer? _statusTimer;

  @override
  void initState() {
    super.initState();
    _refreshHidStatus();
    _statusTimer = Timer.periodic(const Duration(seconds: 2), (_) => _refreshHidStatus());
  }

  @override
  void dispose() {
    _statusTimer?.cancel();
    super.dispose();
  }

  Future<void> _refreshHidStatus() async {
    try {
      final status = await _bridge.invokeMapMethod<String, dynamic>('getHidStatus');
      if (!mounted || status == null) return;
      final devices = (status['pairedDevices'] as List<dynamic>? ?? [])
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
      final connected = status['connected'] == true;
      final ready = status['registered'] == true;
      final name = status['device']?.toString() ?? '';
      setState(() {
        _pairedDevices = devices;
        _connected = connected;
        _ready = ready;
        _connectedName = name;
        if (connected) {
          _status = 'Connected to ${name.isNotEmpty ? name : 'TV'}';
        } else if (ready) {
          _status = 'Bluetooth ready — select a paired TV below';
        } else if (!_busy) {
          _status = 'Bluetooth remote is not started';
        }
      });
    } on PlatformException catch (e) {
      if (mounted && !_busy) setState(() => _status = e.message ?? 'Could not read Bluetooth status');
    }
  }

  Future<void> _startRemote() async {
    setState(() { _busy = true; _status = 'Starting Bluetooth HID…'; });
    try {
      await _bridge.invokeMethod<String>('startHid');
      if (!mounted) return;
      setState(() { _ready = true; _status = 'Bluetooth is ready. Checking paired TVs…'; });
      await _refreshHidStatus();
    } on PlatformException catch (e) {
      if (mounted) setState(() { _ready = false; _status = e.message ?? 'Bluetooth HID could not start'; });
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _connectDevice(Map<String, dynamic> device) async {
    final address = device['address']?.toString() ?? '';
    if (address.isEmpty) return;
    setState(() { _busy = true; _status = 'Connecting to ${device['name']}…'; });
    try {
      await _bridge.invokeMethod<bool>('connectDevice', {'address': address});
      if (mounted) setState(() => _status = 'Connection request sent to ${device['name']}…');
      await Future.delayed(const Duration(milliseconds: 700));
      await _refreshHidStatus();
    } on PlatformException catch (e) {
      if (mounted) setState(() => _status = e.message ?? 'Connection failed');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _key(int code) async {
    try { await _bridge.invokeMethod('sendKey', {'key': code}); }
    on PlatformException catch (e) { if (mounted) setState(() => _status = e.message ?? 'Not connected to TV'); }
  }

  Future<void> _consumer(int usage) async {
    try { await _bridge.invokeMethod('sendConsumer', {'usage': usage}); }
    on PlatformException catch (e) { if (mounted) setState(() => _status = e.message ?? 'Not connected to TV'); }
  }

  Widget _keyButton(String label, {int? key, int? usage, IconData? icon, Color? color, double height = 58}) {
    return SizedBox(height: height, child: FilledButton(
      style: FilledButton.styleFrom(
        backgroundColor: color ?? const Color(0xFF242424),
        foregroundColor: Colors.white,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      ),
      onPressed: _connected ? () { if (key != null) _key(key); if (usage != null) _consumer(usage); } : null,
      child: icon != null ? Icon(icon, size: 25) : Text(label, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
    ));
  }

  Widget _pairedDeviceCard(Map<String, dynamic> device) {
    final isConnected = device['connected'] == true;
    final isConnecting = device['connecting'] == true;
    final name = device['name']?.toString() ?? 'Bluetooth device';
    return Card(
      color: const Color(0xFF242424),
      margin: const EdgeInsets.only(bottom: 9),
      child: ListTile(
        leading: const Icon(Icons.tv, color: Color(0xFF269FE5), size: 28),
        title: Text(name, style: const TextStyle(fontWeight: FontWeight.w600)),
        subtitle: Text(isConnected ? 'Connected' : (isConnecting ? 'Connecting…' : 'Paired device')),
        trailing: isConnected
            ? const Icon(Icons.check_circle, color: Color(0xFF42C77A))
            : TextButton(
                onPressed: (_busy || !_ready) ? null : () => _connectDevice(device),
                child: const Text('CONNECT'),
              ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('TV Remote', style: TextStyle(fontWeight: FontWeight.bold)),
        centerTitle: true,
        actions: [IconButton(onPressed: _refreshHidStatus, tooltip: 'Refresh devices', icon: const Icon(Icons.refresh))],
      ),
      body: SafeArea(child: ListView(padding: const EdgeInsets.fromLTRB(18, 12, 18, 28), children: [
        Container(
          padding: const EdgeInsets.all(17),
          decoration: BoxDecoration(
            color: const Color(0xFF242424),
            borderRadius: BorderRadius.circular(20),
            border: Border.all(color: _connected ? const Color(0xFF42C77A) : const Color(0xFF383838)),
          ),
          child: Row(children: [
            Icon(_connected ? Icons.bluetooth_connected : Icons.bluetooth, color: _connected ? const Color(0xFF42C77A) : const Color(0xFF269FE5), size: 30),
            const SizedBox(width: 13),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(_status, style: const TextStyle(fontWeight: FontWeight.bold)),
              const SizedBox(height: 4),
              Text(_connected ? 'Remote controls are active.' : 'Start the remote, then connect a paired TV.', style: const TextStyle(color: Colors.white70, fontSize: 12)),
            ])),
            if (_busy) const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
          ]),
        ),
        const SizedBox(height: 12),
        SizedBox(height: 49, child: FilledButton.icon(
          onPressed: _busy ? null : _startRemote,
          icon: const Icon(Icons.bluetooth_searching),
          label: Text(_ready ? 'RESTART BLUETOOTH REMOTE' : 'START BLUETOOTH REMOTE'),
        )),
        const SizedBox(height: 22),
        Row(children: [
          const Expanded(child: Text('BLUETOOTH DEVICES', style: TextStyle(color: Colors.white70, letterSpacing: 1.2, fontSize: 12, fontWeight: FontWeight.bold))),
          TextButton.icon(onPressed: _refreshHidStatus, icon: const Icon(Icons.refresh, size: 17), label: const Text('Refresh')),
        ]),
        if (_pairedDevices.isEmpty)
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(color: const Color(0xFF1D1D1D), borderRadius: BorderRadius.circular(16)),
            child: const Text(
              'No paired Bluetooth devices found. Start the remote, then open HY320mini Bluetooth settings and pair your phone. Return here and tap Refresh.',
              style: TextStyle(color: Colors.white70, height: 1.5),
            ),
          )
        else
          ..._pairedDevices.map(_pairedDeviceCard),
        const SizedBox(height: 22),
        const Text('REMOTE CONTROL', style: TextStyle(color: Colors.white70, letterSpacing: 1.2, fontSize: 12, fontWeight: FontWeight.bold)),
        const SizedBox(height: 12),
        Center(child: SizedBox(width: 220, child: _keyButton('▲', key: 0x52, height: 58))),
        Center(child: SizedBox(width: 220, child: Row(children: [
          Expanded(child: _keyButton('◀', key: 0x50)), const SizedBox(width: 9),
          Expanded(child: _keyButton('OK', key: 0x28, color: const Color(0xFF176FA5))), const SizedBox(width: 9),
          Expanded(child: _keyButton('▶', key: 0x4F)),
        ]))),
        Center(child: SizedBox(width: 220, child: _keyButton('▼', key: 0x51))),
        const SizedBox(height: 18),
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
        const SizedBox(height: 20),
        const Text(
          'First-time pairing: start the remote and approve the discoverable prompt. On HY320mini, open Bluetooth settings and pair with “Bluetooth Remote”. Once paired, select the TV from the device list above.',
          style: TextStyle(color: Colors.white54, fontSize: 12, height: 1.5),
        ),
      ])),
    );
  }
}
