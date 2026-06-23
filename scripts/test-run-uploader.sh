#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="$SCRIPT_DIR/run-uploader.sh"
TMP_DIR="${TMPDIR:-/tmp}/rdf4j-uploader-runner-test-$$"

fail() {
	printf 'FAIL: %s\n' "$1" >&2
	exit 1
}

reset_tmp() {
	rm -rf "$TMP_DIR"
	mkdir -p "$TMP_DIR/bin" "$TMP_DIR/log"
}

write_fakes() {
	cat > "$TMP_DIR/bin/mvn" <<'FAKE_MVN'
#!/usr/bin/env bash
set -euo pipefail
: "${TEST_LOG_DIR:?}"
for arg in "$@"; do
	printf '<%s>\n' "$arg"
done > "$TEST_LOG_DIR/mvn.args"
printf '%s\n' "$(pwd)" > "$TEST_LOG_DIR/mvn.cwd"
mkdir -p target
printf 'jar\n' > target/rdf4j-uploader-1.0.0.jar
FAKE_MVN
	cat > "$TMP_DIR/bin/java" <<'FAKE_JAVA'
#!/usr/bin/env bash
set -euo pipefail
: "${TEST_LOG_DIR:?}"
for arg in "$@"; do
	printf '<%s>\n' "$arg"
done > "$TEST_LOG_DIR/java.args"
printf '%s\n' "$(pwd)" > "$TEST_LOG_DIR/java.cwd"
FAKE_JAVA
	chmod +x "$TMP_DIR/bin/mvn" "$TMP_DIR/bin/java"
}

make_repo() {
	local repo="$TMP_DIR/repo"
	mkdir -p "$repo/scripts" "$repo/src/main/java/com/example" "$repo/target"
	[[ -f "$RUNNER" ]] || fail "missing $RUNNER"
	cp "$RUNNER" "$repo/scripts/run-uploader.sh"
	chmod +x "$repo/scripts/run-uploader.sh"
	printf '<project />\n' > "$repo/pom.xml"
	printf 'class App {}\n' > "$repo/src/main/java/com/example/App.java"
	(cd "$repo" && pwd)
}

run_runner() {
	local repo="$1"
	shift
	(
		cd "$TMP_DIR"
		PATH="$TMP_DIR/bin:$PATH" TEST_LOG_DIR="$TMP_DIR/log" "$repo/scripts/run-uploader.sh" "$@"
	)
}

assert_args() {
	local file="$1"
	local expected="$2"
	local actual
	actual="$(cat "$file")"
	[[ "$actual" == "$expected" ]] || fail "unexpected args in $file: $actual"
}

trap 'rm -rf "$TMP_DIR"' EXIT

reset_tmp
write_fakes
repo="$(make_repo)"
run_runner "$repo" --endpoint http://example.test "two words"
assert_args "$TMP_DIR/log/mvn.args" '<package>'
assert_args "$TMP_DIR/log/java.args" "<-jar>
<$repo/target/rdf4j-uploader-1.0.0.jar>
<--endpoint>
<http://example.test>
<two words>"
assert_args "$TMP_DIR/log/java.cwd" "$repo"

reset_tmp
write_fakes
repo="$(make_repo)"
printf 'jar\n' > "$repo/target/rdf4j-uploader-1.0.0.jar"
touch -t 202401010000 "$repo/pom.xml" "$repo/src/main/java/com/example/App.java"
touch -t 202501010000 "$repo/target/rdf4j-uploader-1.0.0.jar"
run_runner "$repo" --help
[[ ! -e "$TMP_DIR/log/mvn.args" ]] || fail "fresh jar should skip Maven"
assert_args "$TMP_DIR/log/java.args" "<-jar>
<$repo/target/rdf4j-uploader-1.0.0.jar>
<--help>"

reset_tmp
write_fakes
repo="$(make_repo)"
printf 'jar\n' > "$repo/target/rdf4j-uploader-1.0.0.jar"
touch -t 202401010000 "$repo/target/rdf4j-uploader-1.0.0.jar"
touch -t 202501010000 "$repo/src/main/java/com/example/App.java"
run_runner "$repo"
assert_args "$TMP_DIR/log/mvn.args" '<package>'

printf 'scripts/test-run-uploader.sh: ok\n'
