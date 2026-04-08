.PHONY: build install clean

build:
	./gradlew assembleDebug

install:
	adb install -r app/build/outputs/apk/debug/app-debug.apk

clean:
	./gradlew clean
