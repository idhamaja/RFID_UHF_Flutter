import 'package:flutter/material.dart';

class InfoCard extends StatelessWidget {
  const InfoCard({
    super.key,
    required this.uniqueCount,
    required this.totalHits,
    required this.elapsedMs,

    bool beepEnabled = true,
    bool vibrateEnabled = true,
  });

  final int uniqueCount;
  final int totalHits;
  final int elapsedMs;

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 2,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceAround,
          children: [
            _InfoItem('Tag Population', '$uniqueCount'),
            _InfoItem('Recognition\nfrequency', '$totalHits'),
            _InfoItem('Inventory\ntime(ms)', '$elapsedMs'),
          ],
        ),
      ),
    );
  }
}

class _InfoItem extends StatelessWidget {
  const _InfoItem(this.label, this.value);
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Text(
          label,
          textAlign: TextAlign.center,
          style: textTheme.bodySmall?.copyWith(color: Colors.black54),
        ),
        const SizedBox(height: 4),
        Text(
          value,
          style: textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold),
        ),
      ],
    );
  }
}
