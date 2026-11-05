# Upstream wrapper pin — termux-ai compat contract

The compat surface is defined against fixed upstream revisions. The wrapper
scripts vendored under `fixtures/upstream-pin/` are byte-identical copies of
the upstream files at these commits (verified with `cmp` at vendoring time;
`sha256sum` below for re-verification):

| Repo | Pinned commit |
|---|---|
| `termux-api` (app side, output contracts) | `760c1777950d69d87b20c0147588b7b660f29135` |
| `termux-api-package` (client C + wrapper scripts) | `0e3f9222eea7760c76ea6368dadbdf884ab85fbf` |

The C client (`termux-api-package/termux-api.c`) installs as
`$PREFIX/libexec/termux-api` (CMakeLists.txt:90-127); the wrapper scripts
install in `$PREFIX/bin`. The wrappers keep the `@TERMUX_PREFIX@` placeholder
exactly as upstream ships it, so the files stay byte-identical to the pin; the
test harness materializes runnable copies in its own temporary prefix by
substituting the placeholder at runtime.

## Why these scripts matter

They are the *contract source* for the compat entry: every supported option,
the exact `libexec/termux-api <Method>` invocation, the stdin/argv precedence
rules and the trailing-newline trimming behavior of each wrapper are read from
these files, not inferred. `MATRIX.md` cites them per method.

## Vendored files and SHA256

```
482320752044b86a20529a46a035a47021c8c0c6a89eb76a0fb15c21a32cb95a  termux-audio-info.in
805f73394696f968e02ace6db70b6ee133b5a6350646059bf506d5ed9b025a7a  termux-battery-status.in
2597d073e17e04025e89f0d743a4840300c8e528d42dd2b9048b440b89291f0a  termux-brightness.in
3b28fa24d39291ee9dc2bd4397b016a6bc7c05d2c0d7220d8cc59654c6977692  termux-camera-info.in
e3558cdb78d834031f28bfe2c29f2efb8980f704fbd5192e928e1bcbba68f0c0  termux-clipboard-get.in
ad72e3cafac632e17151758860673b5a3e5be10878ca087bf4f2aa942c4c180e  termux-clipboard-set.in
097e30e01343c953e2b3f22b68b8f467d09e2ff69e85e18a6bdafe01b977ae3e  termux-notification.in
3e0cda63d264097490fd4504f753297d0f34b868af99dc96c7b4e0eb2b142bcf  termux-notification-channel.in
d416f1a18440755f877824f94a0736d391162e2a936fbe88ab2e0a4734d35a21  termux-notification-remove.in
e716775e30358755438372189d489396b123cf387d61befe50ad4514fb3c2ac7  termux-toast.in
f5856aec319007a12ebe3b08887935207810071705daabbdbd582a5497b9613b  termux-torch.in
7cf07b04b2267a2a62c32672efbc4c43db736fbd4848db3dafe88a680f82ce96  termux-tts-engines.in
114130b37dd9aeb3d6ec8866e192d005239895a017dbbf1de0498404cff4642f  termux-tts-speak.in
309f7d4765c77c12bdaf7eeb221850520210e0edb221757f3d6d972f519eb1bb  termux-vibrate.in
690861896d878e45d103ba61ae761332cf1f067e165d95f0ac2533c3cf9a87d0  termux-volume.in
0417e3a3ba06c21ca8690d75d5f3f9b0ba63f49636ab9e2a2962c68ed3ccfa75  termux-wifi-connectioninfo.in
```

## License

`termux-api-package` is MIT licensed (`Copyright (c) 2017-2021 Termux`). The
vendored copies keep the upstream content unmodified; the pin table above is
the required attribution trail.

## Re-verification

```sh
cd tests/compat/fixtures/upstream-pin
sha256sum -c <<'EOF'
# paste the table above, or compare directly against a checkout of the pin:
# cmp termux-volume.in <path-to-termux-api-package-at-0e3f9222>/scripts/termux-volume.in
EOF
```
