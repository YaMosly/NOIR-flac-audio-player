# Pinned sources

- Media3 decoder_flac Java renderer/decoder and JNI: tag 1.11.1.
  https://github.com/androidx/media/tree/1.11.1/libraries/decoder_flac
  Native wrapper sources are unchanged. FlacDecoderJni adds the UnstableApi annotation for app-module lint. FlacExtractor and its
  seeker are omitted; the existing Media3 FLAC extractor emits encoded frames
  and LibflacAudioRenderer decodes them. PREFER mode selects this renderer.
- libFLAC: tag 1.5.0, release source archive from xiph/flac.
  https://github.com/xiph/flac/tree/1.5.0
  SHA-256 of downloaded tag zip: dc14b44202860c4eea88ee96dcbab17f31d25d906aab9f40784a00fcd40501cf
- Parent CMake disables programs, tests, docs, Ogg and C++ library, and links
  the BSD-licensed C library statically into flacJNI. The full source archive
  retains its upstream license files. No GPL command-line tool is in the APK.
- Binary notices: app/src/main/assets/THIRD_PARTY_AUDIO_LICENSES.txt.
