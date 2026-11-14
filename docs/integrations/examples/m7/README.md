# Ejemplos locales no homologados — M7

Estos ocho JSON fueron creados para esta verificación usando exclusivamente el contrato
genérico M2 v1.79 y los schemas locales v1.78. **No son fixtures oficiales ni eventos
reales proporcionados por M7. No acreditan un acuerdo bilateral.**

Todos los UUID, textos, clasificaciones y fechas son ficticios de tests. SYSTEM/null
evita atribuir identidades a usuarios de Tránsito. El slug `transito` proviene de la
tabla de módulos de M2 v1.79 y del mapeo vigente de M2; falta ratificación M7/Core.
`example.invalid` no es un servicio de evidencias y nunca se descarga.

`routed-local.json` y `routed-anonymous-local.json` ilustran salida M2. Los otros seis
archivos representan escenarios de compatibilidad potencial de entrada, sin afirmar
que M7 publique esas variantes. PROGRESS contiene sólo evidencia, permitido por M2.
La integración a BD reemplaza ticketId/eventId por identificadores del test aislado.
No agregar etiquetas al envelope: el schema prohíbe propiedades adicionales; la
procedencia de estos archivos se declara aquí y en los nombres de archivos y tests.
