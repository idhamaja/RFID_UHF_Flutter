import 'package:flutter/material.dart';

class InfoCard extends StatelessWidget {
  const InfoCard({
    super.key,
    required this.uniqueCount,
    required this.totalHits,
    required this.elapsedMs,
  });

  final int uniqueCount;
  final int totalHits;
  final int elapsedMs;

  @override
  Widget build(BuildContext context) {
    // ms -> s (pembulatan ke bawah)
    final int seconds = (elapsedMs / 1000).floor();
    final String timeLabel = '$seconds s';

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
        tile('Inventory time (s)', timeLabel), // <— label jadi (s)
      ],
    );
  }
}
