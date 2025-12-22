import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import '../../../../core/platform/platform_channels.dart';
import '../../domain/entities/step_data.dart';
import 'notification_datasource.dart';

/// DataSource para acelerómetro usando EventChannel
///
/// - EventChannel se usa para STREAMS de datos continuos
/// - A diferencia de MethodChannel (petición/respuesta),
///   EventChannel envía datos constantemente
abstract class AccelerometerDataSource {
  Stream<StepData> get stepStream;
  Future<void> startCounting();
  Future<void> stopCounting();
  Future<bool> requestPermissions();
}

class AccelerometerDataSourceImpl implements AccelerometerDataSource {
  /// EventChannel: para recibir stream de datos
  final EventChannel _eventChannel = const EventChannel(
    PlatformChannels.accelerometer
  );

  /// MethodChannel auxiliar: para control (start/stop)
  final MethodChannel _methodChannel = const MethodChannel(
    '${PlatformChannels.accelerometer}/control'

  );

    final _notificationDataSource = NotificationDataSource();
  int _lastNotifiedStep = 0;  // Trackear último paso notificado

  @override
  Stream<StepData> get stepStream {
    /// receiveBroadcastStream(): crea un stream que recibe
    /// datos continuamente desde el lado Android
    return _eventChannel.receiveBroadcastStream().map((event) {
      final data = event as Map<dynamic, dynamic>;
  
      // 🚨 DETECTAR CAÍDA
      if (data['type'] == 'fall_detected') {
        print('🚨 CAÍDA DETECTADA - Enviando notificación');
        _notificationDataSource.showNotification(
          '⚠️ CAÍDA DETECTADA',
          'Se detectó un impacto fuerte. Magnitud: ${(data['magnitude'] as num).toStringAsFixed(1)} m/s²',
        );
        // Retornar datos de caída
        return StepData(
          stepCount: 0, 
          activityType: ActivityType.stationary, 
          magnitude: (data['magnitude'] as num).toDouble()
        );
      }
  
      // Datos normales de pasos
      final stepData = StepData.fromMap(data);
      print('👟 Pasos: ${stepData.stepCount}');
  
      // 🔔 NOTIFICACIÓN: Cada 30 pasos (30, 60, 90, 120...)
      if (stepData.stepCount > 0 && 
          stepData.stepCount % 30 == 0 && 
          stepData.stepCount != _lastNotifiedStep) {
        print('🎉 META ALCANZADA - ${stepData.stepCount} pasos - Enviando notificación');
        _lastNotifiedStep = stepData.stepCount;
        _notificationDataSource.showNotification(
          '🎉 Meta Alcanzada',
          '¡Has completado ${stepData.stepCount} pasos!',
        );
      }
      
      return stepData;
    });
  }

  @override
  Future<void> startCounting() async {
    print('▶️ Iniciando contador - Reseteando tracking de notificaciones');
    _lastNotifiedStep = 0;  // Resetear tracking al iniciar
    await _methodChannel.invokeMethod('start');
  }

  @override
  Future<void> stopCounting() async {
    await _methodChannel.invokeMethod('stop');
  }

  @override
  Future<bool> requestPermissions() async {
    final activityStatus = await Permission.activityRecognition.request();
    final sensorsStatus = await Permission.sensors.request();
    return activityStatus.isGranted && sensorsStatus.isGranted;
  }
}
