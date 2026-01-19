import 'package:logger/logger.dart';
import 'package:flutter/foundation.dart';

/// GO-ON Application Logger
///
/// Centralized logging utility for the entire application.
/// Provides structured logging with different severity levels.
///
/// Usage:
/// ```dart
/// AppLogger.d('Debug message');
/// AppLogger.i('Info message');
/// AppLogger.w('Warning message');
/// AppLogger.e('Error message', error, stackTrace);
/// ```
class AppLogger {
  static final Logger _logger = Logger(
    printer: _GoOnLogPrinter(),
    level: kDebugMode ? Level.debug : Level.info,
    filter: _GoOnLogFilter(),
  );

  // Tag for filtering logs
  static String _currentTag = 'GO-ON';

  /// Set a tag for subsequent log messages
  static void setTag(String tag) {
    _currentTag = tag;
  }

  /// Reset tag to default
  static void resetTag() {
    _currentTag = 'GO-ON';
  }

  /// Debug level logging
  /// Use for detailed debugging information
  static void d(String message, [String? tag]) {
    _logger.d('${tag ?? _currentTag}: $message');
  }

  /// Info level logging
  /// Use for general information about app flow
  static void i(String message, [String? tag]) {
    _logger.i('${tag ?? _currentTag}: $message');
  }

  /// Warning level logging
  /// Use for potentially problematic situations
  static void w(String message, [String? tag]) {
    _logger.w('${tag ?? _currentTag}: $message');
  }

  /// Error level logging
  /// Use for errors that need attention
  static void e(String message, [dynamic error, StackTrace? stackTrace, String? tag]) {
    _logger.e(
      '${tag ?? _currentTag}: $message',
      error: error,
      stackTrace: stackTrace,
    );
  }

  /// Fatal level logging
  /// Use for critical errors that may crash the app
  static void fatal(String message, [dynamic error, StackTrace? stackTrace, String? tag]) {
    _logger.f(
      '${tag ?? _currentTag}: $message',
      error: error,
      stackTrace: stackTrace,
    );
  }

  /// Log with custom level
  static void log(Level level, String message, [String? tag]) {
    _logger.log(level, '${tag ?? _currentTag}: $message');
  }

  // ============================================================
  // SPECIALIZED LOGGERS
  // ============================================================

  /// Log native service operations
  static void native(String message, {bool isError = false}) {
    if (isError) {
      e(message, null, null, 'NativeService');
    } else {
      d(message, 'NativeService');
    }
  }

  /// Log price-related operations
  static void price(String message, {double? price, String? app}) {
    final details = <String>[];
    if (price != null) details.add('price=$price');
    if (app != null) details.add('app=$app');
    final suffix = details.isNotEmpty ? ' [${details.join(', ')}]' : '';
    i('$message$suffix', 'Price');
  }

  /// Log automation steps
  static void automation(String message, {String? state, String? app}) {
    final details = <String>[];
    if (state != null) details.add('state=$state');
    if (app != null) details.add('app=$app');
    final suffix = details.isNotEmpty ? ' [${details.join(', ')}]' : '';
    d('$message$suffix', 'Automation');
  }

  /// Log navigation events
  static void navigation(String message, {String? route}) {
    final suffix = route != null ? ' -> $route' : '';
    d('$message$suffix', 'Navigation');
  }

  /// Log API calls
  static void api(String message, {String? endpoint, int? statusCode}) {
    final details = <String>[];
    if (endpoint != null) details.add('endpoint=$endpoint');
    if (statusCode != null) details.add('status=$statusCode');
    final suffix = details.isNotEmpty ? ' [${details.join(', ')}]' : '';
    d('$message$suffix', 'API');
  }

  /// Log authentication events
  static void auth(String message, {bool isError = false}) {
    if (isError) {
      e(message, null, null, 'Auth');
    } else {
      i(message, 'Auth');
    }
  }

  /// Log permission-related events
  static void permission(String message, {String? permissionType, bool? granted}) {
    final details = <String>[];
    if (permissionType != null) details.add('type=$permissionType');
    if (granted != null) details.add('granted=$granted');
    final suffix = details.isNotEmpty ? ' [${details.join(', ')}]' : '';
    i('$message$suffix', 'Permission');
  }
}

/// Custom log printer for GO-ON
class _GoOnLogPrinter extends LogPrinter {
  static final _levelEmojis = {
    Level.debug: '🔍',
    Level.info: 'ℹ️',
    Level.warning: '⚠️',
    Level.error: '❌',
    Level.fatal: '💀',
  };

  static final _levelPrefixes = {
    Level.debug: 'DEBUG',
    Level.info: 'INFO',
    Level.warning: 'WARN',
    Level.error: 'ERROR',
    Level.fatal: 'FATAL',
  };

  @override
  List<String> log(LogEvent event) {
    final emoji = _levelEmojis[event.level] ?? '📝';
    final prefix = _levelPrefixes[event.level] ?? 'LOG';
    final timestamp = DateTime.now().toString().substring(11, 23); // HH:mm:ss.SSS

    final List<String> lines = [];

    // Main message
    lines.add('$emoji [$timestamp] $prefix: ${event.message}');

    // Error details
    if (event.error != null) {
      lines.add('   Error: ${event.error}');
    }

    // Stack trace (limited to 5 lines in debug mode)
    if (event.stackTrace != null && kDebugMode) {
      final stackLines = event.stackTrace.toString().split('\n').take(5);
      for (final line in stackLines) {
        if (line.trim().isNotEmpty) {
          lines.add('   $line');
        }
      }
    }

    return lines;
  }
}

/// Custom log filter for GO-ON
class _GoOnLogFilter extends LogFilter {
  @override
  bool shouldLog(LogEvent event) {
    // In release mode, only log warnings and above
    if (kReleaseMode) {
      return event.level.index >= Level.warning.index;
    }
    // In debug mode, log everything
    return true;
  }
}

/// Extension for easy logging from any class
extension LoggerExtension on Object {
  void logDebug(String message) => AppLogger.d(message, runtimeType.toString());
  void logInfo(String message) => AppLogger.i(message, runtimeType.toString());
  void logWarning(String message) => AppLogger.w(message, runtimeType.toString());
  void logError(String message, [dynamic error, StackTrace? stackTrace]) =>
      AppLogger.e(message, error, stackTrace, runtimeType.toString());
}
