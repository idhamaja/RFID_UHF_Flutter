import 'package:flutter/material.dart';
import 'package:rfid_03/uhf/method_channel_uhf_adapter.dart';
import 'inventory_controller.dart';
import 'widgets/action_buttons.dart';
import 'widgets/epc_table.dart';
import 'widgets/info_card.dart';

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
    ctrl = InventoryController(MethodChannelUhfAdapter());
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
                InfoCard(
                  uniqueCount: ctrl.uniqueTagCount,
                  totalHits: ctrl.totalHitCount,
                  elapsedMs: ctrl.elapsedMilliseconds,
                ),
                const SizedBox(height: 8),
                Expanded(child: EpcTable(rows: ctrl.rows)),
                const SizedBox(height: 8),

                //
                Row(
                  children: [
                    Expanded(
                      child: SwitchListTile(
                        title: const Text("Beep"),
                        value: beepEnabled,
                        onChanged: (val) {
                          setState(() => beepEnabled = val);
                          ctrl.setBeepEnabled(val);
                        },
                      ),
                    ),
                    Expanded(
                      child: SwitchListTile(
                        title: const Text("Vibrate"),
                        value: vibrateEnabled,
                        onChanged: (val) {
                          setState(() => vibrateEnabled = val);
                          ctrl.setVibrateEnabled(val);
                        },
                      ),
                    ),
                  ],
                ),
                ActionButtons(
                  isRunning: ctrl.isRunning,
                  onStart: () {
                    print("▶️ Start button clicked");
                    ctrl.start();
                  },
                  onStop: ctrl.stop,
                  onClear: ctrl.clear,
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}
