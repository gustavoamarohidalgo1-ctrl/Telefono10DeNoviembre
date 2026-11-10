# Teléfono Lite

- App Android nativa Java sin dependencias de producción. Usar subagentes `gpt-6.1-terra` con contexto propio cuando ayuden.
- Mantener el alcance: llamadas entrantes/salientes, audio, controles básicos e historial reciente solicitado por el usuario. Leer CallLog en segundo plano, máximo 100 filas, sin copia privada ni escritura. Contacts Provider es la única agenda; no copiarla ni modificar contactos.
- Diseño exclusivamente blanco, negro y grises neutros. Mantener Colgar visible al abrir el teclado durante una llamada.
- Respetar el bloqueador com.gustavo.silenciollamadas: nunca solicitar ROLE_CALL_SCREENING ni cambiar su configuración o decidir qué llamadas permite. Probar integración con ese APK solo en emulador.
- No cambiar No molestar, modo de timbre ni volúmenes. Telecom controla el timbre; InCallService anuncia UI=true y RINGING=false.
- No añadir Internet, publicidad, telemetría, receptores de arranque ni servicios permanentes.
- No realizar llamadas de red reales ni desinstalar componentes del teléfono sin autorización específica. Probar llamadas sintéticas en emulador.
- Ejecutar release, lint y pruebas funcionales relevantes. Diferenciar resultados del emulador de verificaciones físicas pendientes.
- No publicar `.signing/`, `.local.env` ni archivos locales. Conservar la clave para futuras actualizaciones.
