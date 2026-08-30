#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
working_directory="$(mktemp -d)"
trap 'rm -rf "$working_directory"' EXIT

copy="$working_directory/repository"
mkdir -p "$copy"
tar --exclude=.git --exclude=target -C "$repository_root" -cf - . \
  | tar -C "$copy" -xf -

cd "$copy"
./mvnw -B -DskipTests package
java -jar target/jrefine.jar --profile high-confidence --apply src
./mvnw -B test
java -jar target/jrefine.jar --profile high-confidence src

if ! diff -ru --exclude=target "$repository_root/src" "$copy/src"; then
  echo "Dogfood fixes changed source. Apply those changes to the working tree." >&2
  exit 1
fi
