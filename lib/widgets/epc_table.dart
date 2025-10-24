import 'package:flutter/material.dart';
import 'package:rfid_03/inventory_controller.dart';

class EpcTable extends StatelessWidget {
  const EpcTable({
    super.key,
    required this.rows,
    this.maxVisible = 500,
    this.rowHeight = 44.0,
    this.controller,
  });

  final List<TagRow> rows;
  final int maxVisible;
  final double rowHeight;
  final ScrollController? controller;

  @override
  Widget build(BuildContext context) {
    final data = rows.length > maxVisible ? rows.sublist(0, maxVisible) : rows;

    return Card(
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          Container(
            height: 44,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            color: Colors.grey.shade200,
            child: const Row(
              children: [
                _HeaderCell('SN', flex: 1),
                _HeaderCell('EPC Data', flex: 6),
                _HeaderCell('Num', flex: 2, align: TextAlign.center),
                _HeaderCell('RSSI (dBm)', flex: 2, align: TextAlign.center),
              ],
            ),
          ),
          Expanded(
            child: LayoutBuilder(
              builder: (context, constraints) {
                final unbounded = constraints.maxHeight == double.infinity;
                if (data.isEmpty) {
                  return const Center(child: Text('Belum ada tag terbaca'));
                }
                return ListView.builder(
                  primary: false, // <- penting: selalu false
                  shrinkWrap: unbounded, // true hanya jika unbounded
                  physics: const AlwaysScrollableScrollPhysics(),
                  controller: controller,
                  itemExtent: rowHeight,
                  cacheExtent: rowHeight * 30,
                  addAutomaticKeepAlives: false,
                  addRepaintBoundaries: true,
                  padding: EdgeInsets.zero,
                  itemCount: data.length,
                  itemBuilder: (context, index) {
                    final row = data[index];
                    final alt = (index & 1) == 1;
                    final int rssiDisplay = row.lastRssi.clamp(-60, -30);
                    return Container(
                      key: ValueKey(row.epc),
                      color: alt ? Colors.grey.shade50 : Colors.white,
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      alignment: Alignment.centerLeft,
                      child: Row(
                        children: [
                          const SizedBox(width: 4),
                          _DataCell('${index + 1}', flex: 1),
                          _DataCell(row.epc, flex: 6, mono: true),
                          _DataCell(
                            '${row.count}',
                            flex: 2,
                            align: TextAlign.center,
                          ),
                          _DataCell(
                            '$rssiDisplay',
                            flex: 2,
                            align: TextAlign.center,
                          ),
                        ],
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _HeaderCell extends StatelessWidget {
  const _HeaderCell(this.text, {this.flex = 1, this.align = TextAlign.left});
  final String text;
  final int flex;
  final TextAlign align;

  @override
  Widget build(BuildContext context) => Expanded(
    flex: flex,
    child: Text(
      text,
      textAlign: align,
      style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
    ),
  );
}

class _DataCell extends StatelessWidget {
  const _DataCell(
    this.text, {
    this.flex = 1,
    this.mono = false,
    this.align = TextAlign.left,
  });
  final String text;
  final int flex;
  final bool mono;
  final TextAlign align;

  @override
  Widget build(BuildContext context) => Expanded(
    flex: flex,
    child: Text(
      text,
      overflow: TextOverflow.ellipsis,
      textAlign: align,
      style: TextStyle(fontSize: 12, fontFamily: mono ? 'RobotoMono' : null),
    ),
  );
}
