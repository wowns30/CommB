package com.ssafy.commb.controller;

import com.ssafy.commb.common.QueryStringArgResolver;
import com.ssafy.commb.common.fcm.FcmService;
import com.ssafy.commb.dto.book.BookDto;
import com.ssafy.commb.dto.book.KeywordDto;
import com.ssafy.commb.dto.bookshelf.BookShelfCntDto;
import com.ssafy.commb.dto.bookshelf.BookShelfDto;
import com.ssafy.commb.dto.fcm.FcmDto;
import com.ssafy.commb.dto.feed.FeedDto;
import com.ssafy.commb.dto.user.MyDto;
import com.ssafy.commb.dto.user.UserDto;
import com.ssafy.commb.exception.ApplicationException;
import com.ssafy.commb.jwt.SecurityService;
import com.ssafy.commb.repository.UserRepository;
import com.ssafy.commb.service.*;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.annotation.Nullable;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @ User, Bookshelves, Keyword/Follow Recommend Function Controller
 */
@RestController
@RequestMapping(value = "/api/users")
@Api("User Controller API V1")
public class UserController {
    @Autowired
    private ProfileService profileService;

    @Autowired
    private SecurityService securityService;

    @Autowired
    private UserService userService;

    @Autowired
    private FeedService feedService;

    @Autowired
    private BookService bookService;

    @Autowired
    private KeywordService keywordService;

    @Autowired
    private S3Service s3Service;

    @Autowired
    private ConfirmationTokenService confirmationTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisService redisService;

    @Autowired
    private FcmService fcmService;

    @Value("${security.accesstoken}")
    private String accessToken;

    @Value("${security.refreshtoken}")
    private String refreshToken;

    @Value("${dynamic.path}")
    private String dynamicPath;

    @Value("${dynamic.front.path}")
    private String dynamicFrontPath;

    @GetMapping("/info")
    @ApiOperation(value = "(?????????)?????? ?????? ????????? ??????", response = UserDto.Response.class)
    public ResponseEntity findUserList(@RequestParam String nickname,
                                                             @RequestParam Integer page,
                                                             HttpServletRequest request) {

        switch (userService.getUserRole((int) request.getAttribute("userId"))) {
            case "USR":
                return ResponseEntity.ok().body(userService.getUsers(nickname, page * 50, request));
            case "ADM":
                UserDto.ResponseList userResList = userService.getUsers(nickname, page * 50);
                return ResponseEntity.ok().body(userResList);
        }

        return ResponseEntity.status(401).build();
    }



//    // ????????????(?????????) - (?????????) ?????? ??????
//    @GetMapping("")
//    @ApiOperation(value = "(?????????)?????? ?????? ????????? ??????", response = UserDto.Response.class)
//    public ResponseEntity<UserDto.ResponseList> findUserList(@RequestParam String nickname) {
//
//        UserDto.ResponseList userResList = userService.getUsers(nickname);
//
//        return new ResponseEntity<UserDto.ResponseList>(userResList, HttpStatus.OK);
//    }

    @PostMapping("")
    @ApiOperation(value = "?????? ????????????")
    public ResponseEntity<Map<String, Integer>> singUp(@RequestBody @Valid MyDto.Request myReq, BindingResult bindingResult) {
        if (bindingResult.hasErrors())
            return ResponseEntity.status(400).build();

        if (userService.isExistEmail(myReq.getEmail()))
            return ResponseEntity.status(409).build();

        userService.validatePassword(myReq.getPassword());

        Map<String, Integer> map = new HashMap<>();
        map.put("id", userService.joinUser(myReq));
        return new ResponseEntity<>(map, HttpStatus.valueOf(201));
    }

    @GetMapping("/email")
    @ApiOperation(value = "Email ?????? ??????")
    public ResponseEntity duplicateEmail(@RequestParam String email) {
        if (userService.isExistEmail(email)) return new ResponseEntity(HttpStatus.valueOf(400));

        return new ResponseEntity(HttpStatus.valueOf(200));
    }

    @PostMapping("/confirm-email")
    @ApiOperation(value = "Email ??????")
    public ResponseEntity viewConfirmEmail(@RequestBody MyDto.TokenRequest myTokenReq){
        userService.TokenGeneration(myTokenReq.getId(), myTokenReq.getEmail(), "");

        return ResponseEntity.ok().build();
    }

    @GetMapping("/checkEmailComplete")
    @ApiOperation(value = "Email ?????? ??????")
    public String checkEmailComplete(@RequestParam String key, String url, HttpServletResponse response) throws IOException {
        switch(url){
            case "" :
                if(userService.confirmEmail(key)) response.sendRedirect(dynamicFrontPath + url);
                break;
            case "reset-password" :
                if(userService.confirmEmailForPassword(key)) response.sendRedirect(dynamicFrontPath + url + "?key=" + key);
                break;
            default: return "???????????? ?????? url ?????????.";
        }
        return "<h4>?????? ????????? ?????? ????????? ?????????????????? ???????????? ?????? ????????? ?????????????????????.</h4>";
    }

    @GetMapping("/social/login")
    @ApiOperation(value = "?????? ????????????", response = MyDto.Response.class)
    public ResponseEntity<MyDto.Response> socialLogin(@RequestParam(value="code") String code,
                                                    @RequestParam(value="firebaseToken") String firebaseToken
    ) {
        String userId = redisService.getStringValue(code);

        if(userId == null) throw new ApplicationException(HttpStatus.valueOf(401), "????????? ??????");

        Integer id = Integer.parseInt(userId);

        MyDto.Response myRes = userService.socialLogin(id);
        Map<String, Object> map = securityService.createToken(id);


        HttpHeaders resHeader = new HttpHeaders();
        resHeader.set(accessToken, (String) map.get("acToken"));
        resHeader.set(refreshToken, (String) map.get("rfToken"));

        if(firebaseToken != null) fcmService.save(myRes, firebaseToken);

        return ResponseEntity.ok().headers(resHeader).body(myRes);
    }

    @DeleteMapping("/login")
    @ApiOperation(value = "????????????")
    public ResponseEntity logout(@RequestParam String firebaseToken){
        fcmService.del(firebaseToken);
        return ResponseEntity.status(204).build();
    }

    @PostMapping("/login")
    @ApiOperation(value = "?????? ?????????", response = MyDto.Response.class)
    public ResponseEntity<MyDto.Response> login(@RequestBody MyDto.LoginRequest myReq) throws InterruptedException, IOException {
        MyDto.Response myRes = userService.login(myReq);
        Map<String, Object> map = securityService.createToken(myRes.getData().getId());

        HttpHeaders resHeader = new HttpHeaders();
        resHeader.set(accessToken, (String) map.get("acToken"));
        resHeader.set(refreshToken, (String) map.get("rfToken"));

        if(myReq.getFirebaseToken() != null) fcmService.save(myRes, myReq.getFirebaseToken());

        return ResponseEntity.ok().headers(resHeader).body(myRes);
    }

    @GetMapping(value="/{userId}")
    @ApiOperation(value = "?????? ?????? ??????")
    public ResponseEntity<UserDto.Response> userInfo(@PathVariable Integer userId, HttpServletRequest request){
        UserDto.Response userRes = userService.getUserInfo(userId, request);

        return ResponseEntity.ok().body(userRes);
    }

    @GetMapping("/find-password")
    @ApiOperation(value = "???????????? ?????? ??????")
    public ResponseEntity findUser(@RequestParam String email) {
        int userId = userService.getUserInfoByEmail(email);
        userService.TokenGeneration(userId, email, "reset-password");

        return ResponseEntity.ok().build();
    }

    @PutMapping("/update-password")
    @ApiOperation(value = "???????????? ????????? ?????? Password ?????? ??????")
    public ResponseEntity updatePassword(@RequestBody Map<String, Object> map) {
        String key = (String) map.get("key");
        String password = (String) map.get("password");

        userService.validatePassword(password);

        int userId = confirmationTokenService.findById(key);
        userService.updatePassword(userId, password, 0);

        return ResponseEntity.ok().build();
    }

    /**
     * @ RequestBody Parse request of JSON type Object, So do not use in Multipart/Form-Data
     * @ flag   0: ??????,  1: ??????,  2: ??????
     */
    @PostMapping("/{userId}")
    @ApiOperation(value="????????? ??????")
    public ResponseEntity updateUser(@PathVariable Integer userId,
                                     MyDto.ModifyRequest myReq,
                                     MultipartHttpServletRequest request) throws IOException, ServletException {
        if(myReq != null){
            if(myReq.getNickname().length() < 2 || myReq.getNickname().length() > 10) return new ResponseEntity(HttpStatus.valueOf(400));
        }
        else return ResponseEntity.status(400).build();

        MyDto.Response myRes = profileService.updateProfile(myReq, request);
        if( myRes == null ) return new ResponseEntity("??? ??? ?????? ???????????? ???????????? ?????? ??????!", HttpStatus.valueOf(401));

        return ResponseEntity.ok().body(myRes);
    }

    @PutMapping("/{userId}")
    @ApiOperation(value = "???????????? ??????")
    public ResponseEntity updateUserInfo(@PathVariable("userId") Integer userId,
                                         @RequestBody UserDto.ModifyPwRequest userReq,
                                        HttpServletRequest request) {
        if(userReq == null) return ResponseEntity.status(401).build();
        userService.validatePassword(userReq.getNewPassword());

        userService.updatePassword(userReq, request);

        return new ResponseEntity(HttpStatus.valueOf(200));
    }

    @DeleteMapping("/withdraw")
    @ApiOperation(value = "????????????")
    public ResponseEntity deleteUser(HttpServletRequest request) {
        int userId = (int) request.getAttribute("userId");
        s3Service.deleteS3(userRepository.findUserById(userId).get().getFileUrl(), "profile");

        int p = 0;
        List<FeedDto> feeds = new ArrayList<>();
        while(feeds.size() >= 20 || p == 0){
            feeds = feedService.getUserFeed((int) request.getAttribute("userId"), p++, request).getData();
            s3Service.deleteS3(feeds.stream().map(FeedDto::getFeedFileUrl).collect(Collectors.toList()), "feed");
        }
        userService.deleteUser(userId);


        return new ResponseEntity(HttpStatus.valueOf(204));
    }

    @GetMapping("/{userId}/feeds")
    @ApiOperation(value = "1???(?????? ??????) ?????? ????????? ??????", response = FeedDto.Response.class)
    public ResponseEntity<FeedDto.ResponseList> findUserFeed(
            @PathVariable("userId") Integer userId,
            @RequestParam Integer page,
            HttpServletRequest request
    ) {
        FeedDto.ResponseList feedResList = feedService.getUserFeed(userId, page * 20, request);

        return new ResponseEntity<FeedDto.ResponseList>(feedResList, HttpStatus.OK);
    }

    @GetMapping("/{userId}/feeds/cnt")
    @ApiOperation(value = "????????? ?????? ??????")
    public ResponseEntity<Map<String, Integer>> findUserFeedCnt(
            @PathVariable("userId") Integer userId
    ) {
        HashMap<String, Integer> map = new HashMap<>();
        map.put("cnt", feedService.getUserFeedCnt(userId));

        return new ResponseEntity<>(map, HttpStatus.OK);
    }

    //??????, ?????????
    // users/{userId}/bookshelves/all
    @GetMapping("/{userId}/bookshelves/all")
    @ApiOperation(value = "?????????/?????? ?????? ?????? ????????????")
    public ResponseEntity getBookshelfAll(@PathVariable Integer userId,
                                         @RequestParam Integer isRead){

        List<BookDto> books = bookService.getBookshelfAll(userId, isRead);

        return ResponseEntity.ok().body(BookDto.ResponseList.builder().data(books).build());
    }

    @GetMapping("/{userId}/bookshelves")
    @ApiOperation(value = "?????????/?????? ??? ?????? ??????", response = BookDto.Response.class)
    public ResponseEntity<BookDto.ResponseList> findUserBookShelvesList(
            @PathVariable("userId") Integer userId,
            @QueryStringArgResolver BookDto.BookShelfSearchRequest bookReq,
            @RequestParam Integer page,
            HttpServletRequest request
    ) {
        BookDto.ResponseList bookResList = bookService.getBooksByName(bookReq, page * 20, request);

        return ResponseEntity.ok().body(bookResList);
    }

    @PostMapping("/{userId}/bookshelves")
    @ApiOperation(value = "?????????/?????? ?????? ??????")
    public ResponseEntity insertUserBookShelves(
            @PathVariable("userId") Integer userId,
            @RequestBody BookDto.RegisterRequest book,
            HttpServletRequest request
    ) {
        bookService.addMyShelf(book, request);
        return new ResponseEntity(HttpStatus.valueOf(201));
    }

    @GetMapping("/{userId}/bookshelves/cnt")
    @ApiOperation(value = "??????/??????(??????/?????????) ?????? ???", response = BookShelfCntDto.Response.class)
    public ResponseEntity<BookShelfCntDto.Response> findUserBookShelvesCnt(
            @PathVariable("userId") Integer userId
    ) {
        return new ResponseEntity<>(bookService.getUserReadCnt(userId), HttpStatus.OK);
    }

    @DeleteMapping("/{userId}/bookshelves/{bookId}")
    @ApiOperation(value = "??????/????????? ?????? 1??? ????????????")
    public ResponseEntity deleteUserBookShelf(
            @PathVariable("userId") Integer userId,
            @PathVariable("bookId") Integer bookId,
            HttpServletRequest request
    ) {
        bookService.deleteBookInBookShelf(bookId, request);
        return ResponseEntity.status(204).build();
    }

    @PutMapping("/{userId}/bookshelves/{bookId}")
    @ApiOperation(value = "????????? <-> ?????? ?????? ??????")
    public ResponseEntity updateUserBookShelf(
            @PathVariable("userId") Integer userId,
            @PathVariable("bookId") Integer bookId,
            @RequestBody @Nullable Map<String, Double> map,
            HttpServletRequest request
    ) {
        if(map == null || map.get("rate") == null)  bookService.moveBook(bookId, 0, request);       // ?????? -> ?????????
        else if(map.get("rate") != null) bookService.moveBook(bookId, map.get("rate"), request);        // ????????? -> ??????

        return new ResponseEntity(HttpStatus.OK);
    }

    @GetMapping("/{userId}/bookshelves/{bookId}")
    @ApiOperation(value = "?????????/?????? ?????? ?????? ??????")
    public ResponseEntity findUserBookShelf(
            @PathVariable("userId") Integer userId,
            @PathVariable("bookId") Integer bookId
    ) {
        return new ResponseEntity<BookShelfDto.Response>(bookService.getBookShelf(userId, bookId), HttpStatus.OK);
    }

    @GetMapping("/{userId}/top-bar")
    @ApiOperation(value = "?????? ??? ?????? ?????? ??????", response = BookDto.Response.class)
    public ResponseEntity<BookDto.ResponseList> findUserTopBar(
            @PathVariable("userId") Integer userId
    ) {
        BookDto.ResponseList bookResList = bookService.getTopBooks(userId);

        return new ResponseEntity<BookDto.ResponseList>(bookResList, HttpStatus.OK);
    }

    @PostMapping("/{userId}/top-bar")
    @ApiOperation(value = "?????? ?????? ?????? ??????")
    public ResponseEntity InsertUserTopBar(
            @PathVariable("userId") Integer userId,
            @RequestBody BookDto.TopBarRegisterRequest bookReq,
            HttpServletRequest request
    ) {
        bookService.addBookTop(bookReq, request);
        return ResponseEntity.status(201).body(bookReq.getId());
    }

    @DeleteMapping("/{userId}/top-bar")
    @ApiOperation(value = "?????? ??? ?????? ?????? ??????")
    public ResponseEntity deleteUserTopBarAll(
            @PathVariable("userId") Integer userId,
            HttpServletRequest request
    ) {
        bookService.deleteAllBookTop(request);

        return new ResponseEntity(HttpStatus.valueOf(204));
    }

    @DeleteMapping("/{userId}/top-bar/{bookId}")
    @ApiOperation(value = "?????? ??? ?????? 1??? ??????")
    public ResponseEntity deleteUserTopBar(
            @PathVariable("userId") Integer userId,
            @PathVariable("bookId") Integer bookId,
            HttpServletRequest request
    ) {
        bookService.deleteBookTop(bookId, request);

        return new ResponseEntity(HttpStatus.valueOf(204));
    }

    @GetMapping("/{userId}/follow-recommend")
    @ApiOperation(value = "?????? ?????? ?????? ??????", response = UserDto.Response.class)
    public ResponseEntity<UserDto.ResponseList> findFollowRecommend(
            @PathVariable("userId") Integer userId,
            @RequestParam Integer page,
            HttpServletRequest request
    ) {
        UserDto.ResponseList userResList = userService.followRecommend(page * 50, request);

        return new ResponseEntity<UserDto.ResponseList>(userResList, HttpStatus.OK);
    }

    @GetMapping("/{userId}/keyword-recommend")
    @ApiOperation(value = "?????? ????????? ?????????", response = KeywordDto.Response.class)
    public ResponseEntity<KeywordDto.ResponseList> findKeywordRecommend(
            @PathVariable("userId") Integer userId,
            HttpServletRequest request
    ) {
        KeywordDto.ResponseList keyResList =  keywordService.keywordRecommend(request);

        return new ResponseEntity<KeywordDto.ResponseList>(keyResList, HttpStatus.OK);
    }

    @GetMapping("/alarms")
    @ApiOperation(value = "?????? ?????? ?????? ??????")
    public ResponseEntity getAlarms(HttpServletRequest request,
                                    @RequestParam Integer page){
        List<FcmDto> alarms = userService.getAlarms(page * 20, request);

        return ResponseEntity.ok().body(alarms);
    }

    @GetMapping("/alarms/all")
    @ApiOperation(value = "?????? ?????? ?????? ??????")
    public ResponseEntity getAllAlarms(HttpServletRequest request,
                                    @RequestParam Integer page){
        List<FcmDto> alarms = userService.getAllAlarms(page * 20, request);

        return ResponseEntity.ok().body(alarms);
    }

    @PutMapping("/pencil")
    @ApiOperation(value = "pencil ?????? ?????? ??????")
    public ResponseEntity pencil(HttpServletRequest request){
        userService.updatePencil(request);

        return ResponseEntity.ok().build();
    }

    @PutMapping("/bookmark")
    @ApiOperation(value = "bookmark ?????? ?????? ??????")
    public ResponseEntity bookmark(HttpServletRequest request){
        userService.updateBookmark(request);

        return ResponseEntity.ok().build();
    }
}



/*

<!-- The core Firebase JS SDK is always required and must be listed first -->
<script src="https://www.gstatic.com/firebasejs/8.9.1/firebase-app.js"></script>

<!-- TODO: Add SDKs for Firebase products that you want to use
     https://firebase.google.com/docs/web/setup#available-libraries -->

<script>
  // Your web app's Firebase configuration
  var firebaseConfig = {
    apiKey: "AIzaSyBBx4cBxQU_YDA4IWx11dT6UXwtvrQgdE4",
    authDomain: "commb-27f77.firebaseapp.com",
    projectId: "commb-27f77",
    storageBucket: "commb-27f77.appspot.com",
    messagingSenderId: "1096431511822",
    appId: "1:1096431511822:web:8098fb0674c582d68acf5f"
  };
  // Initialize Firebase
  firebase.initializeApp(firebaseConfig);
</script>



AAAA_0hpKQ4:APA91bFyxBKgfKFJwtQCKfodjXI5ANsC6srfmShL3vjDVxAQKIbuqCVvml5dbzdvcuFe6OJhEHiEcXJUmFwvjicOvqhptiWhOQacPX1Gi8AqPP59tiU452lXJR_lSjN5g4LAsGUTZkuQ

BJF9g6SsclFA3hxLFj8YgBgT4vhAaUXL6Mzsad7Dh2nESKkS1cm1jURzUA9hactgtLe9-HGh_uEX4WIGl3D1YPk



 */


//<!-- The core Firebase JS SDK is always required and must be listed first -->
//<script src="https://www.gstatic.com/firebasejs/8.9.1/firebase-app.js"></script>
//
//<!-- TODO: Add SDKs for Firebase products that you want to use
//        https://firebase.google.com/docs/web/setup#available-libraries -->
//
//<script>
//// Your web app's Firebase configuration
//  var firebaseConfig = {
//          apiKey: "AIzaSyBi-CjUpqtPjDFgo8jLiwxwpcm0KhWI31g",
//          authDomain: "commb-43e85.firebaseapp.com",
//          projectId: "commb-43e85",
//          storageBucket: "commb-43e85.appspot.com",
//          messagingSenderId: "366820301866",
//          appId: "1:366820301866:web:ef81fde40aeda933d63753"
//          };
//          // Initialize Firebase
//          firebase.initializeApp(firebaseConfig);
//</script>