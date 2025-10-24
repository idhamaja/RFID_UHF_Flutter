import 'package:flutter/material.dart';
import 'package:rfid_03/inventory_controller.dart';
import 'package:rfid_03/uhf/method_channel_uhf_adapter.dart';
import 'package:rfid_03/widgets/epc_table.dart';

void main() => runApp(const IUhfApp());

class IUhfApp extends StatelessWidget {
  const IUhfApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'iUHF (Flutter)',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2B63B5)),
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFFF4F6F9),
      ),
      home: const InventoryPage(),
    );
  }
}

class InventoryPage extends StatefulWidget {
  const InventoryPage({super.key});
  @override
  State<InventoryPage> createState() => _InventoryPageState();
}

class _InventoryPageState extends State<InventoryPage> {
  late final InventoryController ctrl;

  bool beepEnabled = true;
  bool vibrateEnabled = true;

  @override
  void initState() {
    super.initState();
    ctrl = InventoryController(MethodChannelUhfAdapter(useEvents: true));
    Future.microtask(() async {
      await ctrl.setBeepEnabled(beepEnabled);
      await ctrl.setVibrateEnabled(vibrateEnabled);
    });
  }

  @override
  void dispose() {
    ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('iUHF Flutter'),
        backgroundColor: Theme.of(context).colorScheme.primary,
        foregroundColor: Colors.white,
      ),
      body: AnimatedBuilder(
        animation: ctrl,
        builder: (context, _) {
          return Padding(
            padding: const EdgeInsets.all(8.0),
            child: Column(
              children: [
                Row(
                  children: [
                    _chip('Tag Population', '${ctrl.uniqueTagCount}'),
                    const SizedBox(width: 8),
                    _chip('Total Hits', '${ctrl.totalHitCount}'),
                    const SizedBox(width: 8),
                    _chip('First hit', '${ctrl.firstHitSeconds}s'),
                  ],
                ),
                const SizedBox(height: 8),
                Expanded(child: EpcTable(rows: ctrl.rows)),
                const SizedBox(height: 8),
                Row(
                  children: [
                    Expanded(
                      child: SwitchListTile(
                        title: const Text("Beep"),
                        value: beepEnabled,
                        onChanged: (v) {
                          setState(() => beepEnabled = v);
                          ctrl.setBeepEnabled(v);
                        },
                      ),
                    ),
                    Expanded(
                      child: SwitchListTile(
                        title: const Text("Vibrate"),
                        value: vibrateEnabled,
                        onChanged: (v) {
                          setState(() => vibrateEnabled = v);
                          ctrl.setVibrateEnabled(v);
                        },
                      ),
                    ),
                  ],
                ),
                Row(
                  children: [
                    Expanded(
                      child: ElevatedButton(
                        onPressed: ctrl.isRunning ? null : ctrl.start,
                        style: ElevatedButton.styleFrom(
                          padding: const EdgeInsets.all(14),
                        ),
                        child: const Text('START'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: ElevatedButton(
                        onPressed: ctrl.isRunning ? ctrl.stop : null,
                        style: ElevatedButton.styleFrom(
                          padding: const EdgeInsets.all(14),
                        ),
                        child: const Text('STOP'),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: OutlinedButton(
                        onPressed: ctrl.clear,
                        style: OutlinedButton.styleFrom(
                          padding: const EdgeInsets.all(14),
                        ),
                        child: const Text('CLEAR'),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _chip(String title, String value) {
    return Expanded(
      child: Container(
        height: 64,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(12),
          boxShadow: [
            BoxShadow(color: Colors.black.withOpacity(0.04), blurRadius: 8),
          ],
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(fontSize: 12, color: Colors.black54),
            ),
            const SizedBox(height: 4),
            Text(
              value,
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
          ],
        ),
      ),
    );
  }
}
