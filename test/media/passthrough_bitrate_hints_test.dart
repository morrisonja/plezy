import 'package:flutter_test/flutter_test.dart';
import 'package:plezy/media/media_source_info.dart';
import 'package:plezy/media/passthrough_bitrate_hints.dart';

void main() {
  MediaAudioTrack track({required int id, String? codec, int? channels, int? kbps}) =>
      MediaAudioTrack(id: id, codec: codec, channels: channels, bitrateKbps: kbps, selected: false);

  test('lists every stream with a reported bitrate as codec:channels:kbps', () {
    expect(
      passthroughBitrateHintSpec([
        track(id: 1, codec: 'EAC3', channels: 6, kbps: 768),
        track(id: 2, codec: 'ac3', channels: 6, kbps: 448),
        track(id: 3, codec: 'truehd', channels: 8),
      ]),
      'eac3:6:768,ac3:6:448',
    );
  });

  test('is empty when nothing is known and tolerates missing channel counts', () {
    expect(passthroughBitrateHintSpec(const []), '');
    expect(passthroughBitrateHintSpec([track(id: 1, codec: 'eac3', kbps: 0)]), '');
    expect(passthroughBitrateHintSpec([track(id: 1, codec: 'eac3', kbps: 640)]), 'eac3:0:640');
  });
}
