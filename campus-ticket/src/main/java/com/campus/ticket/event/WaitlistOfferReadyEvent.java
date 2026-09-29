package com.campus.ticket.event;

import java.time.LocalDateTime;

public record WaitlistOfferReadyEvent(Long offerId, LocalDateTime deadline)
{
}

