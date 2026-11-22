# Real trains for the 1 Sep 2026 replay

`real-trains-2026-09-01.json` lists the 65 trains that ran on Bern – Thun between 03:30 and 09:30 on 1 Sep 2026, the morning of the Ostermundigen incident (about 04:06 – 08:02).

- **Planned:** trains, stops and platforms come from the official GTFS timetable 2026.
- **Actual:** times, cancelled stops and pass-throughs come from the Ist-Daten v2 archive for September 2026.
- **Sources:** both are open data from [opentransportdata.swiss](https://opentransportdata.swiss). No SBB-export or Discord data is used, so the file can be committed.

Each train follows the backend's `Train` shape. Extra fields, which the backend can ignore:
- per stop: `arrActual`, `depActual`, `cancelled`, `passThrough`
- per train: `cancelled`, `operator`

Rebuild with `sbb-prototype/scripts/build_real_trains.py` (see its docstring). It needs the GTFS zip (~290 MB) and the Ist-Daten month zip (~1.4 GB) from the open-data archive.
