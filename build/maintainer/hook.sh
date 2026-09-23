# SPDX-License-Identifier: Apache-2.0

petal_authorize_build() {
    export PETAL_AUTH_BUILD_TYPE=UNOFFICIAL PETAL_AUTH_AUTHORIZED=false
    export PETAL_AUTH_MAINTAINER=unknown PETAL_AUTH_DEVICE=unknown
    export PETAL_AUTH_REASON=not_checked
    case "${TARGET_PRODUCT:-}" in petal_*) ;; *) return 0 ;; esac
    local arg wants_bacon=false is_build=false result
    for arg in "$@"; do
        case "$arg" in
            --build-mode|--make-mode) is_build=true ;;
            bacon) wants_bacon=true ;;
            TARGET_PRODUCT=*|TARGET_BUILD_VARIANT=*|TARGET_RELEASE=*) return 0 ;;
        esac
    done
    if [[ "$wants_bacon" != true || "$is_build" != true ]]; then return 0; fi
    if result=$(python3 "$TOP/vendor/petal/build/maintainer/client.py" --top "$TOP"); then
        if [[ "$result" =~ ^(OFFICIAL\|true|UNOFFICIAL\|(true|false))\|[A-Za-z0-9_.-]+\|[A-Za-z0-9_.-]+\|[A-Za-z0-9_.-]+$ ]]; then
            IFS='|' read -r PETAL_AUTH_BUILD_TYPE PETAL_AUTH_AUTHORIZED \
                PETAL_AUTH_MAINTAINER PETAL_AUTH_DEVICE PETAL_AUTH_REASON <<< "$result"
            return 0
        fi
    fi
    export PETAL_AUTH_REASON=client_failed
    echo 'petalOS: UNOFFICIAL (authorization client failed)' >&2
    return 0
}

petal_authorize_build "$@"
unset -f petal_authorize_build
