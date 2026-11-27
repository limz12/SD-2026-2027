# TPF replica

`ReplicaService` is a Java 17 client for the E2-03 leader WebSocket hub. It stores only the encrypted `.bin` bytes and their cryptographic `.hdr`; it never receives plaintext records, the leader's master key, or the leader catalog.

Request a distinct `/replica-token` from the configured Discord server for each first-time replica registration, deliver the token privately, then run each replica from this project directory:

```powershell
mvn package
java -cp target/classes tpf.ReplicaService <id> ws://<leader-host>:8765 <quotaBytes> <token>
```

Omit the token only after the registration marker has been persisted by a successful first connection. Each instance uses `dados-<id>/`, stores an Ed25519 PKCS#8 private key and X.509 public key with owner-only permissions, and logs activity to `eventos-<id>.txt`. `ReplicaStore` checks the SHA-256 chunk ID, quota, and atomic publication before the service sends an ACK. Repeated STOREs are idempotent.

The client reconnects with exponential backoff from 1 to 30 seconds, sends heartbeats every two seconds, and publishes its inventory on each authenticated connection.
