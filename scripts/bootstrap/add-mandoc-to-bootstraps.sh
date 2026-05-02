#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work_root="${repo_root}/app/build/termux-ai-mandoc-bootstrap"
bootstrap_dir="${repo_root}/app/src/main/cpp"
mirror_base="${TERMUX_MANDOC_MIRROR:-https://termux.librehat.com/apt/termux-main}"

mkdir -p "$work_root"

declare -A mandoc_sha256=(
  [aarch64]="23da45c8f7e5954f4c7cd4d30631f1b0b6844009b36a1204b1e43bc7d8bd7dd3"
  [arm]="823107dd0870101620e8ee92a807dd9ea1d49c80aeca463bc277fbb6bf501f19"
  [i686]="aebd9521a4df679b3728e777a12201e4a640662cfcc2fe07a21e94e9460dec8e"
  [x86_64]="f78fa2c6875cf82027a6b6d54f17b0a43381e9dfd1a99e76affd66fcc22ea1b6"
)

declare -A mandoc_size=(
  [aarch64]="908"
  [arm]="628"
  [i686]="784"
  [x86_64]="888"
)

download_mandoc() {
  local arch="$1"
  local deb="${work_root}/mandoc_1.14.6-6_${arch}.deb"
  local url="${mirror_base}/pool/main/m/mandoc/mandoc_1.14.6-6_${arch}.deb"

  if [[ -f "$deb" ]]; then
    local actual
    actual="$(sha256sum "$deb" | awk '{print $1}')"
    [[ "$actual" == "${mandoc_sha256[$arch]}" ]] && {
      printf '%s\n' "$deb"
      return
    }
    rm -f "$deb"
  fi

  curl -fsSL "$url" -o "$deb"
  local actual
  actual="$(sha256sum "$deb" | awk '{print $1}')"
  if [[ "$actual" != "${mandoc_sha256[$arch]}" ]]; then
    rm -f "$deb"
    echo "Wrong checksum for $url: expected ${mandoc_sha256[$arch]}, actual $actual" >&2
    exit 1
  fi

  printf '%s\n' "$deb"
}

extract_deb_part() {
  local deb="$1"
  local part_prefix="$2"
  local out_dir="$3"
  local member

  member="$(ar t "$deb" | grep -E "^${part_prefix}\\.tar\\.(xz|zst|gz)$" | head -n1)"
  [[ -n "$member" ]] || {
    echo "Cannot find ${part_prefix}.tar.* in $deb" >&2
    exit 1
  }

  mkdir -p "$out_dir"
  case "$member" in
    *.xz) ar p "$deb" "$member" | tar -xJf - -C "$out_dir" ;;
    *.zst) ar p "$deb" "$member" | tar --zstd -xf - -C "$out_dir" ;;
    *.gz) ar p "$deb" "$member" | tar -xzf - -C "$out_dir" ;;
  esac
}

write_status_entry() {
  local arch="$1"
  local status_file="$2"

  if grep -q '^Package: mandoc$' "$status_file"; then
    return
  fi

  cat >> "$status_file" <<EOF

Package: mandoc
Architecture: ${arch}
Installed-Size: ${mandoc_size[$arch]}
Maintainer: Joshua Kahn @TomJo2000
Version: 1.14.6-6
Homepage: https://mdocml.bsd.lv/
Breaks: man
Depends: less, libandroid-glob, zlib
Replaces: man
Provides: man
Description: Man page viewer from the mandoc toolset
Status: install ok installed
EOF
}

patch_bootstrap() {
  local arch="$1"
  local zip_path="${bootstrap_dir}/bootstrap-${arch}.zip"
  [[ -f "$zip_path" ]] || {
    echo "Missing bootstrap zip: $zip_path" >&2
    exit 1
  }

  if unzip -l "$zip_path" | grep -q ' bin/mandoc$'; then
    echo "bootstrap-${arch}.zip already contains mandoc"
    return
  fi

  local deb
  deb="$(download_mandoc "$arch")"

  local patch_dir="${work_root}/patch-${arch}"
  rm -rf "$patch_dir"
  mkdir -p "$patch_dir/bootstrap" "$patch_dir/data" "$patch_dir/control"

  unzip -q "$zip_path" -d "$patch_dir/bootstrap"
  extract_deb_part "$deb" data "$patch_dir/data"
  extract_deb_part "$deb" control "$patch_dir/control"

  local prefix_dir="$patch_dir/data/data/data/com.termux/files/usr"
  [[ -d "$prefix_dir" ]] || {
    echo "Unexpected mandoc deb layout for $arch" >&2
    exit 1
  }

  rsync -a --copy-links --exclude='bin/apropos' --exclude='bin/makewhatis' \
    --exclude='bin/man' --exclude='bin/whatis' \
    --exclude='share/man/man1/whatis.1.gz' \
    "${prefix_dir}/" "$patch_dir/bootstrap/"

  cat >> "$patch_dir/bootstrap/SYMLINKS.txt" <<'EOF'
mandoc←./bin/apropos
../bin/mandoc←./bin/makewhatis
mandoc←./bin/man
mandoc←./bin/whatis
apropos.1.gz←./share/man/man1/whatis.1.gz
EOF

  mkdir -p "$patch_dir/bootstrap/var/lib/dpkg/info"
  cp "$patch_dir/control/conffiles" "$patch_dir/bootstrap/var/lib/dpkg/info/mandoc.conffiles"
  cp "$patch_dir/control/postinst" "$patch_dir/bootstrap/var/lib/dpkg/info/mandoc.postinst"
  cp "$patch_dir/control/triggers" "$patch_dir/bootstrap/var/lib/dpkg/info/mandoc.triggers"

  (cd "$patch_dir/data" && find . -mindepth 1 | sed 's#^\./#/#' | sort) \
    > "$patch_dir/bootstrap/var/lib/dpkg/info/mandoc.list"

  (cd "$patch_dir/data" && find data -type f -print0 | sort -z | xargs -0 md5sum) \
    > "$patch_dir/bootstrap/var/lib/dpkg/info/mandoc.md5sums"

  write_status_entry "$arch" "$patch_dir/bootstrap/var/lib/dpkg/status"

  local tmp_zip="${zip_path}.tmp"
  rm -f "$tmp_zip"
  (cd "$patch_dir/bootstrap" && TZ=UTC find . -print | sort | zip -q -X -0 "$tmp_zip" -@)
  mv "$tmp_zip" "$zip_path"
  echo "Patched bootstrap-${arch}.zip with mandoc"
}

for arch in aarch64 arm i686 x86_64; do
  patch_bootstrap "$arch"
done
