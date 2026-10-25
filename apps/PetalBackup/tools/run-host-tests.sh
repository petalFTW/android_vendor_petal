#!/usr/bin/env bash
set -euo pipefail

project="$(realpath "$(dirname "$0")/..")"
test_root="$(mktemp -d /tmp/opencode/petal-backup-host.XXXXXX)"
trap 'rm -rf "$test_root"' EXIT

javac -d "$test_root/classes" \
    "$project/src/org/petal/backup/core/OperationControl.java" \
    "$project/src/org/petal/backup/core/OperationJournal.java" \
    "$project/src/org/petal/backup/core/FileSnapshotEngine.java" \
    "$project/src/org/petal/backup/crypto/EncryptedArchive.java" \
    "$project/src/org/petal/backup/crypto/RecoveryKeyEnvelope.java" \
    "$project/src/org/petal/backup/storage/LocalSnapshotRepository.java" \
    "$project/src/org/petal/backup/storage/SnapshotRepository.java" \
    "$project/src/org/petal/backup/privilege/BrokerContract.java" \
    "$project/tests/RepositoryTest.java" \
    "$project/tests/RecoveryTest.java" \
    "$project/tests/OperationJournalTest.java" \
    "$project/tests/BrokerContractTest.java"
java -cp "$test_root/classes" org.petal.backup.tests.RepositoryTest "$test_root"
