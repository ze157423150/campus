package com.campus.ticket.dto;

public record VenueWriteRequest(String name, String address, Integer capacity, String description, String status)
{
}
