# Verificación · 0.2.1

Validación realizada el 10 de noviembre de 2026. Aplicación `pe.gustavo.telefonolite`, versión de paquete 3.

## Compilación y entrega

| Comprobación | Resultado |
| --- | --- |
| Release con R8 y reducción de recursos | Correcto |
| Lint release | 0 errores, 9 advertencias |
| Firma del APK | Verificada |
| APK publicado | 61 764 bytes — 61,8 KB decimales |
| Dependencias de producción externas | Ninguna |
| Permiso de Internet | No solicitado |

Las advertencias de Lint corresponden a textos sin localización, un constructor de vista programática y comprobaciones/configuración de versiones. El tamaño del APK no representa el espacio instalado ni la memoria RAM utilizada.

La [versión publicada](https://github.com/gustavoamarohidalgo1-ctrl/Telefono10DeNoviembre/releases/tag/v0.2.1) incluye el APK firmado y un archivo SHA-256 para comprobar su integridad. La clave de firma permanece privada.

## Pruebas de 0.2.1

Las cinco comprobaciones del [informe del emulador](tests/incoming-v0.2.1.json) pasaron en Android 16/API 36:

- Tarjeta de llamada entrante en la pantalla principal con No molestar activo: responder abre los controles; una llamada activa muestra Volver a la llamada.
- Rechazar desde la tarjeta retira la llamada de la interfaz.
- Aviso superior sobre Inicio cuando No molestar está desactivado.
- Pantalla de respuesta con el dispositivo bloqueado.
- Canal de alta importancia, sin sonido, vibración ni permiso para saltarse No molestar.

La revisión del aviso superior utiliza el estado de SystemUI y una captura, porque UIAutomator omite esa ventana en el emulador. Se revisaron visualmente las pantallas. Todas las llamadas y contactos de las capturas son sintéticos.

## Historial y bloqueador

En 0.2.0 se comprobaron la lectura del historial con y sin permiso, los números privados sin acción y la selección de una fila sin llamar automáticamente. La lista consulta como máximo 100 registros y cancela sus consultas al dejar de estar visible.

También se comprobó la integración con Silencio Llamadas 1.1.0 en emulador. Con bloquear todos activo, se rechazaron llamadas de un contacto y de un número no guardado antes de llegar a la interfaz. Con el bloqueador desactivado, se recibió la llamada. Teléfono Lite ocupa el rol de teléfono; el bloqueador conserva el rol de filtrado.

La versión 0.2.1 conserva estos componentes y añade la tarjeta explícita de respuesta, junto con ajustes en la actualización de las notificaciones.

## Comprobación en un teléfono

0.2.1 se instaló y abrió en un Redmi Note 14 4G con Android 16. Se comprobó la coincidencia del APK instalado y que se conservaran los roles de teléfono/bloqueador y los ajustes de silencio y No molestar. No se efectuó una nueva llamada de red automatizada en esta versión. La prueba manual de audio en ambos sentidos y altavoz se realizó en 0.1.0.

No molestar puede ocultar los avisos flotantes o la pantalla completa incluso con las notificaciones habilitadas. La app respeta esa política; la tarjeta dentro de la pantalla principal permite responder cuando esta se encuentra abierta. No se han probado llamadas reales de emergencia, todas las rutas Bluetooth ni todos los escenarios de múltiples SIM y conferencia.

## Reproducir

Configura primero un emulador aislado, la app predeterminada y los permisos. Añade `platform-tools` del SDK al PATH. Los scripts comprueban que el dispositivo sea un emulador antes de realizar cambios.

```sh
./tools/build.sh
python3 tools/check-incoming-emulator.py --serial emulator-5554
python3 tools/check-recents-emulator.py --serial emulator-5554
python3 tools/smoke-emulator.py --serial emulator-5554
```

Las pruebas de llamadas normales requieren desactivar el bloqueador únicamente en el emulador. La prueba de integración `tools/check-blocker-emulator.py` requiere instalar el bloqueador, asignarle su rol, crear el contacto sintético 5550191 y activar bloquear todos; restaura ese interruptor al finalizar. No se necesita incluir datos de un teléfono personal.

## Referencias

- [InCallService](https://developer.android.com/reference/android/telecom/InCallService).
- [Notificaciones de llamadas](https://developer.android.com/develop/ui/views/notifications/call-style).
- [Canales de notificación](https://developer.android.com/develop/ui/views/notifications/channels).
- [Filtrado de llamadas](https://developer.android.com/reference/android/telecom/CallScreeningService).
