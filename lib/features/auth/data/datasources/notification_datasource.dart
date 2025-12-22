import 'package:flutter/services.dart';
import '../../../../core/platform/platform_channels.dart';

/// DataSource para notificaciones usando Platform Channel nativo
class NotificationDataSource {
  final MethodChannel _channel = const MethodChannel(
    PlatformChannels.notifications,
  );

  /// Mostrar notificación usando NotificationManager de Android
  Future<void> showNotification(String title, String body) async {
    try {
      await _channel.invokeMethod('showNotification', {
        'title': title,
        'body': body,
      });
    } catch (e) {
      print('Error mostrando notificación: $e');
    }
  }
}