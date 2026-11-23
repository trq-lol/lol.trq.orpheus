#!/bin/sh
# Says why Now Playing does or does not work on this machine, step by step. Run after build-adapter.sh and
# native/publisher-macos/build.sh. Prints findings; never fails the caller.
here=$(cd "$(dirname "$0")" && pwd)
dist=$here/../dist/macos-universal
adapter="/usr/bin/perl $dist/mediaremote-adapter.pl $dist/MediaRemoteAdapter.framework"
publisher=$here/../publisher-macos/orpheus-publisher
client=$here/build/out/MediaRemoteAdapterTestClient

echo "== system"
sw_vers
echo "console user: $(stat -f%Su /dev/console 2>/dev/null)"
echo "gui session: $(launchctl managername 2>/dev/null)"

echo "== helper, nothing published"
$adapter get
echo "exit $?"

echo "== helper self-test with the upstream test client (0 = works, 4 = helper cannot read)"
if [ -x "$client" ]; then
    /usr/bin/perl "$dist/mediaremote-adapter.pl" "$dist/MediaRemoteAdapter.framework" "$client" test
    echo "exit $?"
else
    echo "no test client at $client"
fi

echo "== helper, with our publisher playing"
(
    printf 'track\tDiagnose Title\tDiagnose Artist\tDiagnose Album\t\t60000\t3366CC\tmusic\n'
    printf 'state\tplaying\n'
    sleep 8
) | "$publisher" &
sleep 4
$adapter get --no-artwork
echo "exit $?"
wait

echo "== what the library reports with our publisher playing (needs the harness to be installed)"
harness=$here/../../harness/build/install/harness/bin/harness
if [ -x "$harness" ]; then
    (
        printf 'track\tDiagnose Title\tDiagnose Artist\tDiagnose Album\t\t60000\t3366CC\tmusic\n'
        printf 'state\tplaying\n'
        sleep 10
    ) | "$publisher" &
    sleep 2
    "$harness" watch --all --seconds 5 | cut -c1-400
    wait
else
    echo "no harness at $harness"
fi
exit 0
