package com.ge.gevisualaid;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PulseSpeed
{
    SLOW("Slow", 3000),
    MEDIUM("Medium", 1500),
    FAST("Fast", 700);

    private final String name;
    private final int periodMs;

    @Override
    public String toString() { return name; }
}