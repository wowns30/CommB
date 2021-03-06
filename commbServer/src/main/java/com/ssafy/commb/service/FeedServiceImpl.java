package com.ssafy.commb.service;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ssafy.commb.dto.book.BookDto;
import com.ssafy.commb.dto.feed.FeedDto;
import com.ssafy.commb.dto.user.MyDto;
import com.ssafy.commb.dto.user.UserDto;
import com.ssafy.commb.exception.ApplicationException;
import com.ssafy.commb.model.*;
import com.ssafy.commb.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.ssafy.commb.dao.FeedDao;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

@RequiredArgsConstructor
@Service
public class FeedServiceImpl implements FeedService {

    @Autowired
    private FeedRepository feedRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private HashTagRepository hashTagRepository;

    @Autowired
    private DailyEventRepository dailyEventRepository;

    @Autowired
    private WeeklyEventRepository weeklyEventRepository;

    @Autowired
    private FeedDao feedDao;

    @Autowired
    private S3Service S3service;

    @Autowired
    private FollowService followService;

    @PersistenceContext
    private EntityManager entityManager;

    public void uploadFeed(FeedDto.RegisterRequest feedReq, MultipartHttpServletRequest request) throws IOException, ServletException {

        User user = new User();
        Book book = new Book();
        Feed feed = new Feed();

        user.setId((Integer) request.getAttribute("userId"));
        book.setId(feedReq.getBookId());
        feed.setUser(user);
        feed.setBook(book);
        feed.setCreateAt(new Date());
        feed.setContent(feedReq.getContents());

        Part part = S3service.extractFile(request.getParts()); // ?????? ?????? ?????????
        String fileUrl = S3service.uploadS3(part, "feed");
        feed.setFileUrl(fileUrl);

        feedRepository.save(feed);


        // DailyEvent, WeeklyEvent ?????? ???????????? ??????
        List<String> Tags = extractHashTag(feedReq.getContents());

        String function = "upload";
        checkDailyEvent(Tags, feed, function);
        checkWeeklyEvent(feed, function);
    }

    /**
     * ?????? ????????? ?????? ????????? ????????????
     * @param userId : target ?????? ID
     * @param request : ?????? ?????? ID
     * @return : ?????? ?????????
     */
    @Override
    public FeedDto.ResponseList getUserFeed(int userId, int page, HttpServletRequest request) {

        List<FeedDto> feeds = feedDao.userFeed(userId, page, (Integer) request.getAttribute("userId"));

        if (feeds.size() == 0) throw new ApplicationException(HttpStatus.valueOf(204), "end of page");

        for (FeedDto feed : feeds) {
            feed.setHashTags(feedDao.getHashTags(feed.getId()));
            feed.setComments(feedDao.getComments(feed.getId(), (Integer) request.getAttribute("userId")));
        }

        return FeedDto.ResponseList.builder()
                .data(feeds)
                .build();
    }

    /**
     * ?????? ????????? ?????? ??????
     * @param userId : ?????? ID
     * @return : ?????? ??????
     */
    @Override
    public int getUserFeedCnt(int userId) {
        return feedDao.userFeedCnt(userId);
    }

    public void modifyFeed(String content, int feedId) {
        Optional<Feed> feed = feedRepository.findById(feedId);
        if (!feed.isPresent()) throw new ApplicationException(HttpStatus.valueOf(400), "????????? ????????? ????????????!");


        Date[] dates = transformDay(feed.get().getCreateAt());
        List<DailyEvent> dailyEvent = dailyEventRepository.findAllByCreateAtBetween(dates[0], dates[1]);
        if (dailyEvent.size() == 0) System.err.println("DailyEvent??? ????????????!");
        else if (dailyEvent.size() > 1) System.err.println("DailyEvent??? 2??? ?????? ???????????? ????????????!");

        String keyword = dailyEvent.get(0).getKeyword().getKeyword();

        List<String> Tags = extractHashTag(feed.get().getContent());
        String function = "modify";
        Boolean checkDailyEventBefore = dailyEvent(Tags, feed.get(), function, keyword);


        // ????????? content??? feed ???????????? & DailyEvent ?????? ???????????? ??????
        feed.get().setContent(content);
        feedRepository.save(feed.get());

        Tags = extractHashTag(content);
        function = "upload";
        Boolean checkDailyEventAfter = dailyEvent(Tags, feed.get(), function, keyword);


        // ?????? ????????? DailyEvent ?????? ?????? X, ?????? ????????? DailyEvent ?????? ?????? X -> ?????? ??????
        // ?????? ????????? DailyEvent ?????? ?????? X, ?????? ?????? DailyEvent ?????? ?????? O -> ?????? ????????? ????????? ???????????? ??????????????? ???????????? ???????????? 1??????(????????? ?????? ??????) pencil +1
        if (!checkDailyEventBefore && checkDailyEventAfter) {
            int dailyEventFeedCnt = dailyEventFeedCnt(feed.get(), dates, keyword);

            if (dailyEventFeedCnt == 1) {
                User user = userRepository.findById(feed.get().getUser().getId()).get();
                user.setPencil(user.getPencil() + 1);
                userRepository.save(user);
            }
        }

        // ?????? ????????? DailyEvent ?????? ?????? O, ?????? ????????? DailyEvent ?????? ?????? O -> ?????? ??????
        // ?????? ????????? DailyEvent ?????? ?????? O, ?????? ????????? DailyEvent ?????? ?????? X -> ?????? ????????? ????????? ???????????? ??????????????? ???????????? ???????????? 0??????(????????? ?????? ??????) pencil -1
        if (checkDailyEventBefore && !checkDailyEventAfter) {
            int dailyEventFeedCnt = dailyEventFeedCnt(feed.get(), dates, keyword);

            if (dailyEventFeedCnt == 0) {
                User user = userRepository.findById(feed.get().getUser().getId()).get();
                user.setPencil(user.getPencil() - 1);
                userRepository.save(user);
            }
        }

    }

    public int getUserId(int feedId) {
        Optional<Feed> feed = feedRepository.findById(feedId);
        return feed.get().getUser().getId();
    }

    public void deleteFeed(int feedId) {
        Optional<Feed> feed = feedRepository.findById(feedId);
        if (!feed.isPresent()) throw new ApplicationException(HttpStatus.valueOf(400), "????????? ????????? ????????????.");

        // DailyEvent, WeeklyEvent ?????? ???????????? ??????
        List<String> Tags = extractHashTag(feed.get().getContent());

        String function = "delete";
        checkDailyEvent(Tags, feed.get(), function);
        checkWeeklyEvent(feed.get(), function);

        // ?????? ??????
        String url = feed.get().getFileUrl();
        S3service.deleteS3(url, "feed");

        feedRepository.deleteById(feedId);
    }

    public void reportFeed(int feedId, String reason, int userId) {
        Report report = new Report();

        Optional<Feed> feed = feedRepository.findById(feedId);
        Optional<User> user = userRepository.findById(userId);

        if (!feed.isPresent()) throw new ApplicationException(HttpStatus.valueOf(400), "????????? ????????? ????????????.");

        report.setFeed(feed.get());
        report.setUser(user.get());
        report.setReason(reason);

        reportRepository.save(report);

        JPAQueryFactory query = new JPAQueryFactory(entityManager);
        QReport qReport = QReport.report;

        JPAQuery<Long> ret = query.from(qReport)
                .where(qReport.feed.id.eq(feedId))
                .groupBy(qReport.user.id)
                .select(qReport.user.id.count());

        List<Long> reportCnt = ret.fetch();
        if(reportCnt.size() >= 3){
            feed.get().setBlocked(1);
            feedRepository.save(feed.get());
        }
    }

    public FeedDto.ResponseList getFollowingFeeds(int page, int userId) {

        List<FeedDto> feeds = feedDao.getFollowingFeeds(page, userId);

        for (FeedDto feed : feeds) {
            feed.setHashTags(feedDao.getHashTags(feed.getId()));
            feed.setComments(feedDao.getComments(feed.getId(), userId));
        }

        return FeedDto.ResponseList.builder()
                .data(feeds)
                .build();
    }

    public MyDto.ResponseList likeFeeds(int feedId, int page, int userId) {

        Optional<Feed> feed = feedRepository.findById(feedId);

        // ????????? ???????????? ?????? ?????? ??????
        List<Thumb> thumbsUser = feed.get().getThumbsUser();

        List<User> users = new ArrayList<>();
        for (Thumb thumb : thumbsUser) {
            users.add(thumb.getUser());
        }

        List<MyDto> myDtoList = users
                .stream()
                .map(user -> {
                    User u = userRepository.findUserById(user.getId()).get();

                    return MyDto.builder()
                            .id(u.getId())
                            .nickname(u.getNickname())
                            .userFileUrl(u.getFileUrl())
                            .isFollow(followService.isFollow(userRepository.findUserById(userId).get(), u))
                            .build();
                }).sorted(Comparator.comparing(MyDto::getNickname)).sorted(Comparator.comparing(MyDto::getIsFollow).reversed())
                .skip(page * 50)
                .limit(50)
                .collect(Collectors.toList());

        MyDto.ResponseList myResList = MyDto.ResponseList.builder().data(myDtoList).build();

        if(myResList.getData().size() == 0) throw new ApplicationException(HttpStatus.valueOf(204), "end of page");

        return myResList;
    }

    /**
     * ???????????? ?????? ??????(FTS), ????????? ?????? ??????
     * @param searchWord : ?????? ??????(???), spacebar ?????? ????????? ??????
     * @param userId : ?????? ID
     * @return : ?????? ????????? ??????
     */
    public FeedDto.ResponseList getFeeds(String searchWord, int page, int userId) {
        StringBuilder dynamicQuery = new StringBuilder();
        String[] words = searchWord.split(" ");

        int countOfWords = words.length;
        if (countOfWords > 0) for (String word : words) dynamicQuery.append("\"").append(word).append("\" ");
        else dynamicQuery.append("\"\"");

        Map<String, Object> map = new HashMap<>();
        map.put("dynamicQuery", dynamicQuery.toString());
        map.put("userId", userId);
        map.put("countOfWords", countOfWords);
        map.put("page", page);
        List<FeedDto> feeds = feedDao.getFeeds(map);

        if (feeds.size() == 0) throw new ApplicationException(HttpStatus.valueOf(204), "end of page");

        for (FeedDto feed : feeds) {
            feed.setHashTags(feedDao.getHashTags(feed.getId()));
            feed.setComments(feedDao.getComments(feed.getId(), userId));
        }

        FeedDto.ResponseList feedResList = new FeedDto.ResponseList();
        feedResList.setData(feeds);

        return FeedDto.ResponseList.builder()
                .data(feeds)
                .build();
    }

    /**
     * FeedId??? ????????? ???????????? ????????? ?????? ?????? ?????? ??????, ????????? ???????????? ???????????? null return
     * @param feedId : ?????? ID
     * @return : ?????? ??????
     */
    @Override
    public FeedDto getFeedInfo(int feedId) {
        Optional<Feed> feed = feedRepository.findById(feedId);

        AtomicReference<FeedDto> feedDto = new AtomicReference<>();

        feed.ifPresent(select -> {
            feedDto.set(FeedDto.builder()
                    .book(BookDto.builder()
                            .id(select.getBook().getId())
                            .build())
                    .user(UserDto.builder()
                            .id(select.getUser().getId())
                            .build())
                    .feedFileUrl(select.getFileUrl())
                    .id(select.getId())
                    .content(select.getContent())
                    .build());
        });

        return feedDto.get();
    }

    public List<String> extractHashTag(String content) {

        Pattern pattern = Pattern.compile("\\#([0-9a-zA-Z???-???_]*)"); // ????????? ??????????????????????????? ????????? ?????????.
        Matcher matcher = pattern.matcher(content); // ?????? ???????????? ????????? ????????? ?????? true ??????
        String extractHashTag = null;
        List<String> hashTags = new ArrayList<>();

        while (matcher.find()) { // ?????? ???????????? ????????? ???????????? ?????? true ?????? ??? ??? ????????? ??????
            extractHashTag = specialCharacter_replace(matcher.group()); // ????????? ????????? ??????

            if (extractHashTag == null) break;
            if (!hashTags.contains(extractHashTag)) hashTags.add(extractHashTag);
        }

        return hashTags;
    }

    public String specialCharacter_replace(String hashTag) {
        hashTag = StringUtils.replace(hashTag, "#", "");

        if (hashTag.length() < 1) return null;

        return hashTag;
    }

    public Date[] transformDay(Date date) {
        // Date -> LocalDate
        LocalDate feedCreate = date.toInstant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();

        // feed createAt ?????? 0??? 0??? 0??? ~ 23??? 59??? 59???
        LocalDateTime startDateTime = LocalDateTime.of(feedCreate, LocalTime.of(0, 0, 0));
        LocalDateTime endDateTime = LocalDateTime.of(feedCreate, LocalTime.of(23, 59, 59));

        // LocalDate -> Date
        Date startDate = java.sql.Timestamp.valueOf(startDateTime);
        Date endDate = java.sql.Timestamp.valueOf(endDateTime);

        Date[] dates = new Date[2];
        dates[0] = startDate;
        dates[1] = endDate;

        return dates;
    }

    public Date[] transformWeek(Date date) {
        // Date -> LocalDate
        LocalDate feedCreate = date.toInstant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();

        int year = feedCreate.getYear();
        int month = feedCreate.getMonthValue();
        int day = feedCreate.getDayOfMonth();
        int startDay, endDay;
        LocalDate start, end;
        LocalDateTime startDateTime, endDateTime;

        if (1 <= day && day <= 7) {
            startDay = 1;
            endDay = 7;
            start = LocalDate.of(year, month, startDay);
            end = LocalDate.of(year, month, endDay);
        } else if (8 <= day && day <= 14) {
            startDay = 8;
            endDay = 14;
            start = LocalDate.of(year, month, startDay);
            end = LocalDate.of(year, month, endDay);
        } else if (15 <= day && day <= 21) {
            startDay = 15;
            endDay = 21;
            start = LocalDate.of(year, month, startDay);
            end = LocalDate.of(year, month, endDay);
        } else {
            startDay = 22;
            endDay = 31;
            if (month == 2) endDay = 28;
            else if (month == 4 || month == 6 || month == 9 || month == 11) endDay = 30;
            start = LocalDate.of(year, month, startDay);
            end = LocalDate.of(year, month, endDay);
        }
        startDateTime = LocalDateTime.of(start, LocalTime.of(0, 0, 0));
        endDateTime = LocalDateTime.of(end, LocalTime.of(23, 59, 59));

        // LocalDate -> Date
        Date startDate = java.sql.Timestamp.valueOf(startDateTime);
        Date endDate = java.sql.Timestamp.valueOf(endDateTime);

        Date[] dates = new Date[2];
        dates[0] = startDate;
        dates[1] = endDate;

        return dates;
    }

    public Boolean dailyEvent(List<String> Tags, Feed feed, String function, String keyword) {
        Boolean checkDailyEvent = false;

        for (int i = 0; i < Tags.size(); i++) {

            if (keyword.equals(Tags.get(i))) checkDailyEvent = true;
            if (function.equals("upload")) { // ????????? hashTag DB??? ??????
                HashTag hashTag = new HashTag();

                hashTag.setTag(Tags.get(i));
                hashTag.setFeed(feed);

                hashTagRepository.save(hashTag);
            } else if (function.equals("modify")) {
                HashTag hashTag = hashTagRepository.findByTagAndFeedId(Tags.get(i), feed.getId()).get();

                hashTagRepository.delete(hashTag);
            }
        }

        return checkDailyEvent;
    }

    public Integer dailyEventFeedCnt(Feed feed, Date[] dates, String keyword) {
        int dailyEventFeedCnt = 0;
        List<Feed> feeds = feedRepository.findByUserIdAndCreateAtBetween(feed.getUser().getId(), dates[0], dates[1]);

        all:
        for (Feed f : feeds) {
            List<HashTag> hashTags = hashTagRepository.findByFeedId(f.getId());

            for (HashTag hashTag : hashTags) {
                if (keyword.equals(hashTag.getTag())) {
                    dailyEventFeedCnt++;
                    if (dailyEventFeedCnt > 1) break all;
                }
            }
        }

        return dailyEventFeedCnt;
    }

    public void checkDailyEvent(List<String> Tags, Feed feed, String function) {

        Date[] dates = transformDay(feed.getCreateAt());
        List<DailyEvent> dailyEvent = dailyEventRepository.findAllByCreateAtBetween(dates[0], dates[1]);
        if (dailyEvent.size() == 0) System.err.println("DailyEvent??? ????????????!");
        else if (dailyEvent.size() > 1) System.err.println("DailyEvent??? 2??? ?????? ???????????? ????????????!");

        String keyword = dailyEvent.get(0).getKeyword().getKeyword();

        Boolean checkDailyEvent = dailyEvent(Tags, feed, function, keyword);

        // DailyEvent ?????? ????????? ???????????? -> ?????? X
        // DailyEvent ?????? ????????? ??????, ?????? ?????? ?????? ???????????? ??? ??? ????????? ?????? ????????? ???????????? ??????????????? ???????????? ???????????? 1??????(????????? ?????? ??????) pencil +1/-1
        if (checkDailyEvent) {
            int dailyEventFeedCnt = dailyEventFeedCnt(feed, dates, keyword);

            if (dailyEventFeedCnt == 1) {
                User user = userRepository.findById(feed.getUser().getId()).get();
                if (function.equals("upload")) user.setPencil(user.getPencil() + 1);
                else if (function.equals("delete")) user.setPencil(user.getPencil() - 1);
                userRepository.save(user);
            }
        }

    }

    public void checkWeeklyEvent(Feed feed, String function) {

        Date[] dates = transformWeek(feed.getCreateAt());
        Optional<WeeklyEvent> weeklyEvent = weeklyEventRepository.findAllByStartDateLessThanEqualAndEndDateGreaterThanEqual(dates[0], dates[1]);
        if (!weeklyEvent.isPresent()) System.err.println("WeeklyEvent??? ????????????!");
        int eventBookId = weeklyEvent.get().getBook().getId();

        // WeeklyEvent ?????? ????????? ???????????? -> ?????? X
        // WeeklyEvent ?????? ????????? ??????, ?????? ?????? ?????? ???????????? ??? ??? ????????? ?????? ????????? ???????????? ??? ???????????? ???????????? 1??????(????????? ?????? ??????) bookmark +1/-1
        if (eventBookId == feed.getBook().getId()) {
            List<Feed> feeds = feedRepository.findByUserIdAndCreateAtBetween(feed.getUser().getId(), weeklyEvent.get().getStartDate(), weeklyEvent.get().getEndDate());
            int weeklyEventFeedCnt = 0;
            for (Feed f : feeds) {
                if (eventBookId == f.getBook().getId()) {
                    weeklyEventFeedCnt++;
                    if (weeklyEventFeedCnt > 1) break;
                }
            }

            if (weeklyEventFeedCnt == 1) {
                User user = userRepository.findById(feed.getUser().getId()).get();
                if (function.equals("upload")) user.setBookmark(user.getBookmark() + 1);
                else if (function.equals("delete")) user.setBookmark(user.getBookmark() - 1);
                userRepository.save(user);
            }
        }
    }

    public FeedDto.Response searchFeed(int feedId, int userId){

        Optional<Feed> feed = feedRepository.findById(feedId);
        if(!feed.isPresent()) throw new ApplicationException(HttpStatus.valueOf(400), "????????? ???????????? ????????????");

        FeedDto feedDto = feedDao.searchFeed(feedId);

        feedDto.setHashTags(feedDao.getHashTags(feedDto.getId()));
        feedDto.setComments(feedDao.getComments(feedDto.getId(), userId));

        FeedDto.Response feedRes = new FeedDto.Response();
        feedRes.setData(feedDto);

        return feedRes;
    }

}
