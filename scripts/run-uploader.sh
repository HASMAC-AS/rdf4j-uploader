#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ARTIFACT_PREFIX="rdf4j-uploader-"

find_jar() {
	local newest=""
	local jar

	for jar in "$ROOT_DIR"/target/"$ARTIFACT_PREFIX"*.jar; do
		[[ -e "$jar" ]] || continue
		[[ "$(basename "$jar")" == original-* ]] && continue
		if [[ -z "$newest" || "$jar" -nt "$newest" ]]; then
			newest="$jar"
		fi
	done

	[[ -n "$newest" ]] && printf '%s\n' "$newest"
}

sources_changed_since() {
	local jar="$1"
	local source_file

	[[ "$ROOT_DIR/pom.xml" -nt "$jar" ]] && return 0

	if [[ -d "$ROOT_DIR/src" ]]; then
		while IFS= read -r -d '' source_file; do
			[[ "$source_file" -nt "$jar" ]] && return 0
		done < <(find "$ROOT_DIR/src" -type f -print0)
	fi

	return 1
}

jar="$(find_jar || true)"
if [[ -z "$jar" || ! -f "$jar" ]] || sources_changed_since "$jar"; then
	(
		cd "$ROOT_DIR"
		mvn package
	)
	jar="$(find_jar || true)"
fi

if [[ -z "$jar" || ! -f "$jar" ]]; then
	printf 'No runnable jar found under %s/target\n' "$ROOT_DIR" >&2
	exit 1
fi

cd "$ROOT_DIR"
exec java -jar "$jar" "$@"
