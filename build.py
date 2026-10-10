#!/usr/bin/env python3
"""Build with the installed Android SDK and JDK 17; no Gradle/plugin installation."""
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Library/Android/sdk")))
JDK = Path(os.environ.get("CLIPBOARD_JDK", str(Path.home() / "Library/Java/JavaVirtualMachines/temurin-17.0.18/Contents/Home")))
TOOLS = SDK / "build-tools/36.1.0"
ANDROID = SDK / "platforms/android-36/android.jar"
API_SHA256 = "31ef4bd9d8e783354c0706ec29e5bd421011e73d20dbb01e08e9cf2658ba6047"
RUNTIME = {
    "service-101.0.0.aar": ("io/github/libxposed/service/101.0.0/service-101.0.0.aar", "3cc752c159422d83ed417f7406cbb4f4e69b68bf79009189529584b27f3f7802"),
    "interface-101.0.0.aar": ("io/github/libxposed/interface/101.0.0/interface-101.0.0.aar", "cfc424e70c7457a8a26caf13b76fbec8e703398677d2ce3bc3da6d3f162e84f4"),
    "kotlin-stdlib-2.2.10.jar": ("org/jetbrains/kotlin/kotlin-stdlib/2.2.10/kotlin-stdlib-2.2.10.jar", "9c67cc79efd6b9215b49d2a4308f5f3433537376c7c88e89bdd6729bd096e61a"),
}


def run(*args):
    subprocess.run([str(x) for x in args], check=True, cwd=ROOT,
                   env={**os.environ, "JAVA_HOME": str(JDK)})


def main():
    deps = ROOT / "deps"
    deps.mkdir(exist_ok=True)
    aar = deps / "api-101.0.1.aar"
    if not aar.exists():
        with urllib.request.urlopen("https://repo.maven.apache.org/maven2/io/github/libxposed/api/101.0.1/api-101.0.1.aar", timeout=30) as response:
            aar.write_bytes(response.read())
    if hashlib.sha256(aar.read_bytes()).hexdigest() != API_SHA256:
        raise SystemExit("Unexpected libxposed API checksum")
    api = deps / "api-101.0.1.jar"
    with zipfile.ZipFile(aar) as archive:
        api.write_bytes(archive.read("classes.jar"))
    runtime = []
    for filename, (url, checksum) in RUNTIME.items():
        path = deps / filename
        if not path.exists():
            with urllib.request.urlopen("https://repo.maven.apache.org/maven2/" + url, timeout=30) as response:
                path.write_bytes(response.read())
        if hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
            raise SystemExit("Unexpected runtime dependency checksum: " + filename)
        if path.suffix == ".aar":
            jar = path.with_suffix(".jar")
            with zipfile.ZipFile(path) as archive:
                jar.write_bytes(archive.read("classes.jar"))
            runtime.append(jar)
        else:
            runtime.append(path)

    build = ROOT / "build"
    if build.exists():
        shutil.rmtree(build)
    classes, dex = build / "classes", build / "dex"
    classes.mkdir(parents=True)
    dex.mkdir()
    sources = sorted((ROOT / "src").rglob("*.java"))
    classpath = os.pathsep.join(str(x) for x in [ANDROID, api, *runtime])
    run(JDK / "bin/javac", "--release", "17", "-g:none", "-cp", classpath, "-d", classes, *sources)
    compiled = build / "module.jar"
    with zipfile.ZipFile(compiled, "w", zipfile.ZIP_DEFLATED) as jar:
        for path in sorted(classes.rglob("*.class")):
            jar.write(path, path.relative_to(classes))
    run(TOOLS / "d8", "--release", "--min-api", "36", "--lib", ANDROID,
        "--classpath", api, "--output", dex, compiled, *runtime)
    unsigned = build / "unsigned.apk"
    run(TOOLS / "aapt2", "link", "-I", ANDROID, "--manifest", ROOT / "AndroidManifest.xml",
        "--min-sdk-version", "36", "--target-sdk-version", "36", "-o", unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as apk:
        apk.write(dex / "classes.dex", "classes.dex")
        for path in sorted((ROOT / "resources").rglob("*")):
            if path.is_file():
                apk.write(path, path.relative_to(ROOT / "resources"))
    aligned = build / "aligned.apk"
    run(TOOLS / "zipalign", "-f", "4", unsigned, aligned)

    signing = ROOT / "signing"
    signing.mkdir(mode=0o700, exist_ok=True)
    keystore, password_file = signing / "release.p12", signing / "password.txt"
    if not keystore.exists():
        password_file.write_text(secrets.token_urlsafe(32))
        password_file.chmod(0o600)
        run(JDK / "bin/keytool", "-genkeypair", "-keystore", keystore,
            "-storepass:file", password_file, "-keypass:file", password_file,
            "-alias", "clipboard-glass", "-keyalg", "RSA", "-keysize", "3072",
            "-validity", "10000", "-dname", "CN=Clipboard Glass")
        keystore.chmod(0o600)
    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    apk = output / "clipboard-glass-1.6.2.apk"
    run(TOOLS / "apksigner", "sign", "--ks", keystore,
        "--ks-pass", f"file:{password_file}",
        "--out", apk, aligned)
    run(TOOLS / "apksigner", "verify", "--verbose", apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (output / "SHA256SUMS").write_text(f"{digest}  {apk.name}\n")
    print(f"Built {apk} ({apk.stat().st_size:,} bytes)")
    print(f"SHA256 {digest}")


if __name__ == "__main__":
    main()
