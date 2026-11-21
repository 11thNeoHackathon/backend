package com.cheongchun.family;

import static com.cheongchun.family.FamilyModels.*;

import com.cheongchun.common.api.ApiResponse;
import com.cheongchun.common.auth.ParticipantContext;
import com.cheongchun.common.auth.ParticipantContext.ParticipantIdentity;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class FamilyController {
    private final FamilyService family;
    private final boolean secureCookie;

    FamilyController(FamilyService family, @Value("${app.secure-cookie}") boolean secureCookie) {
        this.family = family;
        this.secureCookie = secureCookie;
    }

    @PostMapping(value = "/participants", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<ParticipantIdentity>> register(@RequestBody ProfileInput input) {
        return response(family.register(input));
    }

    @GetMapping("/participants/me")
    ResponseEntity<ApiResponse<ParticipantIdentity>> me() {
        return response(new Reply<>(family.me(), null, false));
    }

    @PostMapping(value = "/books", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<CreatedBook>> createBook(@RequestBody CreateBookInput input) {
        return response(new Reply<>(family.createBook(input), null, true));
    }

    @GetMapping("/books")
    ResponseEntity<ApiResponse<List<BookView>>> listBooks() {
        return response(new Reply<>(family.listBooks(), null, false));
    }

    @GetMapping("/books/{bookId}")
    ResponseEntity<ApiResponse<BookView>> book(@PathVariable long bookId) {
        return response(new Reply<>(family.getBook(bookId), null, false));
    }

    @GetMapping("/invitations/{code}")
    ResponseEntity<ApiResponse<InvitePreview>> preview(@PathVariable String code) {
        return response(new Reply<>(family.previewInvite(code), null, false));
    }

    @PostMapping(value = "/invitations/{code}/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<BookView>> accept(@PathVariable String code, @RequestBody AcceptInput input) {
        return response(family.acceptInvite(code, input));
    }

    private <T> ResponseEntity<ApiResponse<T>> response(Reply<T> reply) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(reply.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore());
        if (reply.newSessionToken() != null) {
            ResponseCookie cookie = ResponseCookie.from(ParticipantContext.COOKIE_NAME, reply.newSessionToken())
                    .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/")
                    .maxAge(ParticipantContext.SESSION_LIFETIME).build();
            response.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return response.body(ApiResponse.success(reply.data()));
    }

}
