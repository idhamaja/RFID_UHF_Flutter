import 'package:flutter/material.dart';

class InfoCard extends StatelessWidget {
  const InfoCard({
    super.key,
    required this.uniqueCount,
    required this.totalHits,
    required this.elapsedMs,
    required this.firstHitSec, // detik
  });

  final int uniqueCount;
  final int totalHits;
  final int elapsedMs;
  final int firstHitSec;

  @override
  Widget build(BuildContext context) {
    final int seconds = (elapsedMs / 1000).floor();
    final String timeLabel = '$seconds s';
    final String firstHitLabel = firstHitSec <= 0 ? '—' : '$firstHitSec s';

    Widget tile(String title, String value) => Expanded(
      child: Container(
        height: 80,
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(16),
          boxShadow: const [BoxShadow(blurRadius: 4, color: Color(0x11000000))],
        ),
        child: FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.center,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                title,
                textAlign: TextAlign.center,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: Colors.black.withOpacity(.6),
                  fontSize: 12,
                  height: 1.1,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                value,
                textAlign: TextAlign.center,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontSize: 28,
                  fontWeight: FontWeight.bold,
                  height: 1.0,
                ),
              ),
            ],
          ),
        ),
      ),
    );

    return Row(
      children: [
        tile('Tag Population', '$uniqueCount'),
        const SizedBox(width: 7),
        tile('Recognition\nfrequency', '$totalHits'),
        const SizedBox(width: 7),
        tile('Inventory time', timeLabel),
        const SizedBox(width: 7),
        tile('First hit', firstHitLabel),
      ],
    );
  }
}
