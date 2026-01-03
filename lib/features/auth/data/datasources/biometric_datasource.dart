import 'package:local_auth/local_auth.dart';
import '../../domain/entities/auth_result.dart';

/// DataSource para autenticación biométrica usando el plugin local_auth
/// - Ya no usamos Platform Channels directamente
/// - El plugin local_auth maneja toda la lógica nativa
abstract class BiometricDataSource {
  Future<bool> canAuthenticate();
  Future<AuthResult> authenticate();
}

class BiometricDataSourceImpl implements BiometricDataSource {
  final LocalAuthentication _auth = LocalAuthentication();

  @override
  Future<bool> canAuthenticate() async {
    try {
      final canAuthenticateWithBiometrics = await _auth.canCheckBiometrics;
      final canAuthenticate = canAuthenticateWithBiometrics || await _auth.isDeviceSupported();
      return canAuthenticate;
    } catch (e) {
      print('Error verificando biometría: $e');
      return false;
    }
  }

  @override
  Future<AuthResult> authenticate() async {
    try {
      final authenticated = await _auth.authenticate(
        localizedReason: 'Por favor autentícate para continuar',
        options: const AuthenticationOptions(
          stickyAuth: true,
          biometricOnly: true,
        ),
      );

      return AuthResult(
        success: authenticated,
        message: authenticated ? 'Autenticación exitosa' : 'Autenticación fallida',
      );
    } catch (e) {
      return AuthResult(
        success: false,
        message: 'Error: $e',
      );
    }
  }
}
