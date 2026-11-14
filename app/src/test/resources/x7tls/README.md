Spec 68 X7 test fixtures: self-signed EC (P-256) leaves in PKCS12, store and key password `x7tls-test`, alias `leaf`.
Made 2026-11-10 with keytool, no new dependency (okhttp-tls is not on the classpath):

    gen(){ keytool -genkeypair -alias leaf -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -storetype PKCS12 \
      -keystore $1.p12 -storepass x7tls-test -keypass x7tls-test -dname "CN=$2" -ext "san=$3" $4; }
    gen a         addon-a.test dns:addon-a.test                 "-validity 36500"
    gen a2        addon-a.test dns:addon-a.test                 "-validity 36500"   # same name, another key
    gen ab        addon-a.test dns:addon-a.test,dns:addon-b.test "-validity 36500"  # covers both names
    gen a-expired addon-a.test dns:addon-a.test                 "-startdate 2020/01/01 -validity 30"

Test-only keys: never trust them anywhere else.
