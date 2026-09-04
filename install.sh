#!/usr/bin/env bash
set -euo pipefail

repository_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
bin_dir=${HOME:?HOME must be set}/.local/bin
data_parent=${XDG_DATA_HOME:-"$HOME/.local/share"}
install_dir=$data_parent/gradle-lsp
distribution_dir=$repository_dir/build/install/gradle-lsp

"$repository_dir/gradlew" --project-dir "$repository_dir" installDist

mkdir -p -- "$bin_dir" "$data_parent"
staging_dir=$(mktemp -d "$data_parent/.gradle-lsp-install.XXXXXX")
cleanup() {
    rm -rf -- "$staging_dir"
}
trap cleanup EXIT

cp -R -- "$distribution_dir/." "$staging_dir/"
rm -rf -- "$install_dir"
mv -- "$staging_dir" "$install_dir"
trap - EXIT

ln -sfn -- "$install_dir/bin/gradle-lsp" "$bin_dir/gradle-lsp"
"$bin_dir/gradle-lsp" --help >/dev/null

echo "Installed gradle-lsp to $bin_dir/gradle-lsp"
if [[ :$PATH: != *":$bin_dir:"* ]]; then
    echo "Add $bin_dir to PATH before starting your editor."
fi
