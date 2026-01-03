import 'package:geolocator/geolocator.dart';
import '../../domain/entities/location_point.dart';

/// DataSource para GPS usando el plugin geolocator
/// - Ya no usamos Platform Channels directamente
/// - El plugin geolocator maneja toda la lógica nativa
abstract class GpsDataSource {
  Future<LocationPoint?> getCurrentLocation();
  Stream<LocationPoint> get locationStream;
  Future<bool> isGpsEnabled();
  Future<bool> requestPermissions();
}

class GpsDataSourceImpl implements GpsDataSource {
  @override
  Future<LocationPoint?> getCurrentLocation() async {
    try {
      // Verificar permisos primero
      final permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied || 
          permission == LocationPermission.deniedForever) {
        print('⚠️ Permisos de ubicación denegados');
        return null;
      }

      final position = await Geolocator.getCurrentPosition(
        desiredAccuracy: LocationAccuracy.high,
      );
      
      print('✅ Ubicación obtenida: ${position.latitude}, ${position.longitude}');
      
      return LocationPoint(
        latitude: position.latitude,
        longitude: position.longitude,
        timestamp: position.timestamp,
      );
    } catch (e) {
      print('❌ Error obteniendo ubicación: $e');
      return null;
    }
  }

  @override
  Stream<LocationPoint> get locationStream {
    const locationSettings = LocationSettings(
      accuracy: LocationAccuracy.high,
      distanceFilter: 5, // metros - reducido para mejor detección
      // Sin timeLimit - dejamos que tome el tiempo que necesite
    );

    return Geolocator.getPositionStream(locationSettings: locationSettings)
        .map((position) {
      print('📍 Stream GPS: ${position.latitude}, ${position.longitude}');
      return LocationPoint(
        latitude: position.latitude,
        longitude: position.longitude,
        timestamp: position.timestamp,
      );
    });
  }

  @override
  Future<bool> isGpsEnabled() async {
    final enabled = await Geolocator.isLocationServiceEnabled();
    print('GPS Enabled: $enabled');
    return enabled;
  }

  @override
  Future<bool> requestPermissions() async {
    print('🔐 Solicitando permisos de ubicación...');
    
    // Verificar permisos actuales
    LocationPermission permission = await Geolocator.checkPermission();
    print('Permiso actual: $permission');
    
    if (permission == LocationPermission.denied) {
      // Solicitar permisos
      permission = await Geolocator.requestPermission();
      print('Permiso después de solicitar: $permission');
    }
    
    if (permission == LocationPermission.deniedForever) {
      print('❌ Permisos denegados permanentemente');
      return false;
    }

    if (permission == LocationPermission.denied) {
      print('❌ Permisos denegados');
      return false;
    }

    print('✅ Permisos concedidos');
    return true;
  }
}
