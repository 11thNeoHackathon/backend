package com.cheongchun.family;

import static com.cheongchun.family.FamilyModels.*;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class FamilyController {
    private static final String COOKIE = "participant_session";

    private final FamilyService family;
    private final boolean secureCookie;

    FamilyController(FamilyService family, @Value("${app.secure-cookie}") boolean secureCookie) {
        this.family = family;
        this.secureCookie = secureCookie;
    }

    @PostMapping(value = "/participants", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Envelope<Participant>> register(@CookieValue(name = COOKIE, required = false) String token,
                                                   @RequestBody ProfileInput input) {
        return response(family.register(token, input));
    }

    @GetMapping("/participants/me")
    ResponseEntity<Envelope<Participant>> me(@CookieValue(name = COOKIE, required = false) String token) {
        return response(new Reply<>(family.me(token), null, false));
    }

    @PostMapping(value = "/books", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Envelope<CreatedBook>> createBook(@CookieValue(name = COOKIE, required = false) String token,
                                                      @RequestBody CreateBookInput input) {
        return response(new Reply<>(family.createBook(token, input), null, true));
    }

    @GetMapping("/books")
    ResponseEntity<Envelope<List<BookView>>> listBooks(@CookieValue(name = COOKIE, required = false) String token) {
        return response(new Reply<>(family.listBooks(token), null, false));
    }

    @GetMapping("/books/{bookId}")
    ResponseEntity<Envelope<BookView>> book(@CookieValue(name = COOKIE, required = false) String token,
                                            @PathVariable long bookId) {
        return response(new Reply<>(family.getBook(token, bookId), null, false));
    }

    @GetMapping("/invitations/{code}")
    ResponseEntity<Envelope<InvitePreview>> preview(@CookieValue(name = COOKIE, required = false) String token,
                                                     @PathVariable String code) {
        return response(new Reply<>(family.previewInvite(token, code), null, false));
    }

    @PostMapping(value = "/invitations/{code}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Envelope<BookView>> accept(@CookieValue(name = COOKIE, required = false) String token,
                                              @PathVariable String code, @RequestBody AcceptInput input) {
        return response(family.acceptInvite(token, code, input));
    }

    private <T> ResponseEntity<Envelope<T>> response(Reply<T> reply) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(reply.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore());
        if (reply.newSessionToken() != null) {
            ResponseCookie cookie = ResponseCookie.from(COOKIE, reply.newSessionToken())
                    .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/")
                    .maxAge(FamilyService.SESSION_LIFETIME).build();
            response.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return response.body(new Envelope<>(reply.data()));
    }

    record Envelope<T>(T data) {}
}
