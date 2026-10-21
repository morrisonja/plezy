import 'media_source_info.dart';

/// The `passthrough-bitrate-hints` value for [tracks]: one `codec:channels:kbps` entry per
/// audio stream whose bitrate the server reported, comma separated, or empty when none did.
///
/// ExoPlayer sizes an E-AC-3 passthrough buffer from it instead of the codec's 6 Mbit/s
/// ceiling, so a DD+ pause stops within half a second rather than playing out the seconds
/// of audio such a buffer holds at a typical bitrate.
String passthroughBitrateHintSpec(Iterable<MediaAudioTrack> tracks) {
  final entries = <String>[];
  for (final track in tracks) {
    final codec = track.codec?.trim().toLowerCase();
    final kbps = track.bitrateKbps;
    if (codec == null || codec.isEmpty || codec.contains(':') || codec.contains(',')) continue;
    if (kbps == null || kbps <= 0) continue;
    entries.add('$codec:${track.channels ?? 0}:$kbps');
  }
  return entries.join(',');
}
