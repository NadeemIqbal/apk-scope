#!/usr/bin/env bash

PACKAGE_NAME="com.nadeem.apkscope"
ADMIN_RECEIVER="$PACKAGE_NAME/.spike.SandboxAdminReceiver"
FIXTURE_PACKAGE="com.apksandbox.pinnedfixture"

TARGET_DEVICE=""

while getopts "s:" opt; do
  case $opt in
    s)
      TARGET_DEVICE="$OPTARG"
      ;;
    \?)
      echo "Invalid option: -$OPTARG" >&2
      exit 1
      ;;
  esac
done

DEVICES=()

if [ -n "$TARGET_DEVICE" ]; then
    DEVICES=("$TARGET_DEVICE")
else
    ALL_DEVICES=()
    while read -r line; do
        if [ -n "$line" ]; then
            ALL_DEVICES+=("$line")
        fi
    done <<< "$(adb devices | awk 'NR>1 && /device$/ {print $1}')"
    
    if [ ${#ALL_DEVICES[@]} -eq 0 ]; then
        echo "❌ No devices connected."
        exit 1
    elif [ ${#ALL_DEVICES[@]} -eq 1 ]; then
        DEVICES=("${ALL_DEVICES[0]}")
    else
        echo "⚠️  More than 1 device/emulator found: ${ALL_DEVICES[*]}"
        read -p "Do you want to continue? It will clean up all connected devices. (y/N) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            DEVICES=("${ALL_DEVICES[@]}")
        else
            echo "Operation cancelled. Run again with '-s <device_id>' to specify a target device."
            exit 1
        fi
    fi
fi

for DEVICE in "${DEVICES[@]}"; do
    echo "=========================================="
    echo "📱 Processing device: $DEVICE"
    echo "=========================================="
    
    ADB_CMD="adb -s $DEVICE"

    echo "🔍 Finding APK Scope work profile..."
    USER_IDS=$($ADB_CMD shell pm list users | grep -i "APK Scope" | grep -o 'UserInfo{[0-9]*:' | grep -o '[0-9]*')

    if [ -z "$USER_IDS" ]; then
        echo "ℹ️  No APK Scope work profile found."
    else
        for USER_ID in $USER_IDS; do
            echo "🗑️  Removing Work Profile User ID: $USER_ID"
            $ADB_CMD shell pm remove-user $USER_ID
        done
        sleep 2
    fi

    echo "🛑 Removing Device Admin if present on default user..."
    $ADB_CMD shell dpm remove-active-admin $ADMIN_RECEIVER 2>/dev/null

    echo "🗑️  Uninstalling Sandbox App ($PACKAGE_NAME)..."
    $ADB_CMD uninstall $PACKAGE_NAME

    echo "🗑️  Uninstalling Pinned Fixture ($FIXTURE_PACKAGE)..."
    $ADB_CMD uninstall $FIXTURE_PACKAGE
done

echo "✅ Cleanup complete!"
