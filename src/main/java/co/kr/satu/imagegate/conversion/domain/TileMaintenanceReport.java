package co.kr.satu.imagegate.conversion.domain;
/** Bounded inventory snapshot; skipped/changed entries can make byte estimates incomplete or conservative. */
public record TileMaintenanceReport(int inspected,int removed,long removedBytes,long retainedBytes,int skipped) {}
