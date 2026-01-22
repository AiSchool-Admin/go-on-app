/// Flutter test configuration
///
/// This file sets up global configuration for all Flutter tests

import 'dart:async';
import 'package:flutter_test/flutter_test.dart';

Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  // Set up global test configuration
  TestWidgetsFlutterBinding.ensureInitialized();

  // Configure default test timeout
  // addTearDown(() {});

  await testMain();
}
