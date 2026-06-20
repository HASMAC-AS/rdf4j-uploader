# rdf4j-uploader
Upload files to RDF4J in parallel

```bash
java -jar rdf4j-uploader-1.0.0.jar \
  --endpoint http://localhost:8080/rdf4j-server \
  --repository myrepo \
  --folder /data/rdf \
  --threads 8 \
  --base-uri http://example.org/ \
  --isolation-level read-committed \
  --resume
```

Supported isolation levels: `none`, `read-uncommitted`, `read-committed`, `snapshot-read`, `snapshot`, `serializable`.
If omitted, uploads use `read-committed`.

Failed uploads are tried up to 10 times total, waiting 10 seconds between attempts. If a file still fails, it is marked failed and the process exits with code `1`.

Progress is tracked every run in `/data/rdf/.rdf4j-uploader-progress.properties`. Pressing `Ctrl-C` stops starting new files, lets active uploads finish, and prints the exact command to resume from that file. Pressing `Ctrl-C` again interrupts active uploads.
Use `--resume` to skip files already marked `UPLOADED`.
Use `--progress-file /path/to/progress.properties` to store progress elsewhere; this also enables resume.
