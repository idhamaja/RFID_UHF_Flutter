import 'package:flutter/material.dart';
import 'package:rfid_03/inventory_controller.dart';

class EpcTable extends StatelessWidget {
  const EpcTable({
    super.key,
    required this.rows,
    this.maxVisible = 1500, // batasi render agar UI tetap ringan
    this.rowHeight = 44.0,
    this.controller, // opsional: jaga posisi scroll
  });

  final List<TagRow> rows;
  final int maxVisible;
  final double rowHeight;
  final ScrollController? controller;

  @override
  Widget build(BuildContext context) {
    // batasi jumlah baris yg dirender; sisanya tetap ada di controller
    final data = rows.length > maxVisible ? rows.sublist(0, maxVisible) : rows;

    return Card(
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          // Header
          Container(
            height: 44,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            color: Colors.grey.shade200,
            child: const Row(
              children: [
                _HeaderCell('SN', flex: 1),
                _HeaderCell('EPC Data', flex: 6),
                _HeaderCell('Num', flex: 2, align: TextAlign.center),
                _HeaderCell('RSSI', flex: 2, align: TextAlign.center),
              ],
            ),
          ),

          // Body — auto handle kasus nested scroll
          Expanded(
            child: LayoutBuilder(
              builder: (context, constraints) {
                final unbounded = constraints.maxHeight == double.infinity;

                if (data.isEmpty) {
                  return const Center(child: Text('Belum ada tag terbaca'));
                }

                return ListView.builder(
                  // jika di dalam scroll lain (unbounded), aktifkan shrinkWrap
                  primary: !unbounded,
                  shrinkWrap: unbounded,
                  physics: const AlwaysScrollableScrollPhysics(),
                  controller: controller,
                  itemExtent: rowHeight, // sangat mempercepat list besar
                  cacheExtent: rowHeight * 30, // pre-render 30 baris di depan
                  addAutomaticKeepAlives: false,
                  addRepaintBoundaries: true,
                  padding: EdgeInsets.zero,
                  itemCount: data.length,
                  itemBuilder: (context, index) {
                    final row = data[index];
                    final alt = (index & 1) == 1;
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
                            '${row.lastRssi}',
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
      overflow: TextOverflow.ellipsis, // tak pecah layout ketika sangat panjang
      textAlign: align,
      style: TextStyle(fontSize: 12, fontFamily: mono ? 'RobotoMono' : null),
    ),
  );
}
