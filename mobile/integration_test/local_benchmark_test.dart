import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:voiceflow_mobile/main.dart' as app;

/// One install/test invocation runs a matrix; each completed result is durable.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  const encoded = String.fromEnvironment('LOCAL_BENCHMARK_MANIFEST');
  testWidgets(
    'local replay matrix',
    (tester) async {
      final manifest =
          jsonDecode(utf8.decode(base64Decode(encoded)))
              as Map<String, dynamic>;
      await app.main();
      await tester.pumpAndSettle();
      const channel = MethodChannel('voiceflow/overlay');
      final output = File(manifest['output'] as String);
      final runs = manifest['runs'] as List<dynamic>;
      var failures = 0;
      for (final item in runs) {
        final run = item as Map<String, dynamic>;
        final row = <String, dynamic>{
          'recordingId': run['recordingId'],
          'configurationId': run['configurationId'],
          'repetition': run['repetition'],
        };
        try {
          final response = await channel.invokeMethod<String>(
            'runLocalBenchmark',
            {'path': run['path'], 'config': run['config']},
          );
          row.addAll(jsonDecode(response!) as Map<String, dynamic>);
        } catch (error) {
          failures++;
          row['error'] = error.toString();
        }
        await output.writeAsString(
          '${jsonEncode(row)}\n',
          mode: FileMode.append,
          flush: true,
        );
        // Pacing/cooldown is intentional and documented in the experiment manifest.
        await Future<void>.delayed(
          Duration(milliseconds: manifest['cooldownMs'] as int? ?? 5000),
        );
      }
      expect(
        failures,
        0,
        reason: 'Inspect saved results; $failures runs failed.',
      );
    },
    skip: encoded.isEmpty,
    timeout: const Timeout(Duration(hours: 24)),
  );
}
