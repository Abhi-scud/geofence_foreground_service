import '../constants/geofence_event_type.dart';
import 'package:latlng/latlng.dart';

/// Function that executes your background trigger.
/// You should return whether the task ran successfully or not.
///
/// [zoneId] Returns the value you provided when registering the geofence.
typedef BackgroundTriggerHandler = Future<bool> Function(
  String zoneId,
  GeofenceEventType triggerType,
  String latitude,
  String longitude,
);
