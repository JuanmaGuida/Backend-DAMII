# Laboratorio P2P con ActiveMQ

Esta POC crea la cola `lab.m2.ticket.work`, publica mensajes JMS `TextMessage`
con body JSON y mantiene dos consumidores competidores mediante un único
`@JmsListener` con concurrencia fija `2-2`.

Todo el código del laboratorio requiere el perfil `p2p-lab` y no utiliza
entidades, servicios, Outbox/Inbox ni eventos funcionales de M2.

## Ejecutar

```powershell
docker compose -f compose.yml -f compose.p2p-lab.yml up -d --build
docker compose -f compose.yml -f compose.p2p-lab.yml logs -f backend
```

En otra terminal PowerShell, enviar doce mensajes:

```powershell
1..12 | ForEach-Object {
    $body = @{
        ticketId = [guid]::NewGuid().ToString()
        publicId = "LAB-{0:D3}" -f $_
        requestType = "ALUMBRADO_PUBLICO"
        status = "CREATED"
        sequence = $_
    } | ConvertTo-Json

    Invoke-RestMethod -Method Post `
        -Uri "http://localhost:8080/api/lab/p2p/messages" `
        -ContentType "application/json" `
        -Body $body
}
```

Cada línea `LAB-P2P` informa secuencia, publicId, JMSMessageID y thread. Un
JMSMessageID debe aparecer una sola vez y los nombres de thread permiten ver
los dos consumidores. La consola del broker queda en http://localhost:8161/admin/.

Para detener el laboratorio sin borrar volúmenes:

```powershell
docker compose -f compose.yml -f compose.p2p-lab.yml down
```
