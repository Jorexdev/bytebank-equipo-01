package dev.bytebank.cards.card;

import dev.bytebank.cards.card.CardDtos.CardResponse;
import dev.bytebank.cards.card.CardDtos.ChangeCardStatusRequest;
import dev.bytebank.cards.card.CardDtos.IssueCardRequest;
import dev.bytebank.cards.card.CardDtos.PaymentRequest;
import dev.bytebank.cards.card.CardDtos.PaymentResponse;
import dev.bytebank.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cards")
@PreAuthorize("hasRole('CLIENTE')")
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse issue(@Valid @RequestBody IssueCardRequest request) {
        return CardResponse.from(cardService.issue(request.iban(), CurrentUser.get()));
    }

    @GetMapping
    public List<CardResponse> listOwn() {
        return cardService.listOwn(CurrentUser.get()).stream().map(CardResponse::from).toList();
    }

    @GetMapping("/{id}")
    public CardResponse get(@PathVariable Long id) {
        return CardResponse.from(cardService.get(id, CurrentUser.get()));
    }

    @PatchMapping("/{id}/status")
    public CardResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeCardStatusRequest request) {
        return CardResponse.from(cardService.changeStatus(id, request.status(), CurrentUser.get()));
    }

    /** Simula el cobro desde un comercio. Responde 201 también si el pago se rechaza: el estado va en el cuerpo. */
    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse pay(@PathVariable Long id, @Valid @RequestBody PaymentRequest request) {
        return PaymentResponse.from(cardService.pay(id, request.amount(), request.merchant(), CurrentUser.get()));
    }

    @GetMapping("/{id}/payments")
    public List<PaymentResponse> payments(@PathVariable Long id) {
        return cardService.payments(id, CurrentUser.get()).stream().map(PaymentResponse::from).toList();
    }
}
