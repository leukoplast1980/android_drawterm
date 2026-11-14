#!/bin/sh
# Create a release signing key for drawterm and print the four GitHub
# secrets the build workflow needs. If the GitHub CLI (gh) is installed and
# logged in, offer to store them in the repository directly.
#
# Needs keytool (comes with any Java JDK).
#
#   tools/make-signing-key.sh [output.jks]
#
# Keep the .jks file and its password somewhere safe: every future update of
# the app must be signed with the same key, or Android will refuse to
# install it over the old one.

set -eu

out=${1:-drawterm-release.jks}
alias=drawterm

if [ -e "$out" ]; then
	echo "$out already exists; not overwriting it." >&2
	exit 1
fi
command -v keytool >/dev/null || { echo "keytool not found: install a Java JDK" >&2; exit 1; }

# A random password, so nobody has to invent one.
pass=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)

keytool -genkeypair -v \
	-keystore "$out" -storetype PKCS12 \
	-storepass "$pass" -keypass "$pass" \
	-alias "$alias" -keyalg RSA -keysize 4096 -validity 10000 \
	-dname "CN=drawterm for Android" >/dev/null

b64=$(base64 <"$out" | tr -d '\n')

echo
echo "Created $out (password: $pass)"
echo "Store both somewhere safe."
echo
echo "Add these as repository secrets (Settings → Secrets and variables → Actions):"
echo
echo "  SIGNING_KEYSTORE_BASE64  (the long line below)"
echo "  SIGNING_STORE_PASSWORD   $pass"
echo "  SIGNING_KEY_ALIAS        $alias"
echo "  SIGNING_KEY_PASSWORD     $pass"
echo
echo "$b64"
echo

if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
	printf 'Store them in the current GitHub repository now with gh? [y/N] '
	read -r answer
	case $answer in
	[yY]*)
		printf '%s' "$b64" | gh secret set SIGNING_KEYSTORE_BASE64
		printf '%s' "$pass" | gh secret set SIGNING_STORE_PASSWORD
		printf '%s' "$alias" | gh secret set SIGNING_KEY_ALIAS
		printf '%s' "$pass" | gh secret set SIGNING_KEY_PASSWORD
		echo "Done. The next build will be signed with this key."
		;;
	esac
fi
