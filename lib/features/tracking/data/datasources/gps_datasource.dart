import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import '../../../../core/platform/platform_channels.dart';
import '../../domain/entities/location_point.dart';

/// DataSource para GPS
///
/// EXPLICACIÓN DIDÁCTICA:
/// - Combina MethodChannel (operaciones puntuales)
/// - Con EventChannel (stream de ubicaciones)
abstract class GpsDataSource {
  Future<LocationPoint?> getCurrentLocation();
  Stream<LocationPoint> get locationStream;
  Future<bool> isGpsEnabled();
  Future<bool> requestPermissions();
}

class GpsDataSourceImpl implements GpsDataSource {
  final MethodChannel _methodChannel = const MethodChannel(
    PlatformChannels.gps
  );

  final EventChannel _eventChannel = const EventChannel(
    '${PlatformChannels.gps}/stream'
  );

  @override
  Future<LocationPoint?> getCurrentLocation() async {
    try {
      final result = await _methodChannel.invokeMethod('getCurrentLocation');
      if (result != null) {
        return LocationPoint.fromMap(result as Map<dynamic, dynamic>);
      }
      return null;
    } on PlatformException catch (e) {
      print('Error obteniendo ubicación: ${e.message}');
      return null;
    }
  }

  @override
  Stream<LocationPoint> get locationStream {
    return _eventChannel.receiveBroadcastStream().map((event) {
      return LocationPoint.fromMap(event as Map<dynamic, dynamic>);
    });
  }

  @override
  Future<bool> isGpsEnabled() async {
    try {
      return await _methodChannel.invokeMethod('isGpsEnabled') ?? false;
    } on PlatformException {
      return false;
    }
  }

  @override
  Future<bool> requestPermissions() async {
    final locationStatus = await Permission.location.request();
    if (!locationStatus.isGranted) {
      final whenInUseStatus = await Permission.locationWhenInUse.request();
      return whenInUseStatus.isGranted;
    }
    return locationStatus.isGranted;
  }
}
