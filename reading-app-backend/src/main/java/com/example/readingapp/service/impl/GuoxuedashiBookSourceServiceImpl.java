package com.example.readingapp.service.impl;

import com.example.readingapp.entity.Book;
import com.example.readingapp.entity.Chapter;
import com.example.readingapp.repository.BookRepository;
import com.example.readingapp.repository.ChapterRepository;
import com.example.readingapp.service.OnlineBookSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuoxuedashiBookSourceServiceImpl implements OnlineBookSourceService {

    private final BookRepository bookRepository;
    private final ChapterRepository chapterRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final String BASE_URL = "https://www.guoxuedashi.com";
    private static final String SOURCE_TYPE = "GUOXUEDASHI";
    private static final String SOURCE_NAME = "国学大师";

    private static final Map<String, String> BOOK_URL_MAP = new HashMap<>();

    static {
        BOOK_URL_MAP.put("红楼梦", "1041o");
        BOOK_URL_MAP.put("西游记", "1046t");
        BOOK_URL_MAP.put("三国演义", "1858x");
        BOOK_URL_MAP.put("水浒传", "1038k");
        BOOK_URL_MAP.put("儒林外史", "1053o");
        BOOK_URL_MAP.put("聊斋志异", "1044o");
        BOOK_URL_MAP.put("史记", "161n");
        BOOK_URL_MAP.put("论语", "350k");
        BOOK_URL_MAP.put("道德经", "524j");
        BOOK_URL_MAP.put("孟子", "352k");
        BOOK_URL_MAP.put("庄子", "373k");
        BOOK_URL_MAP.put("荀子", "379k");
        BOOK_URL_MAP.put("韩非子", "387k");
        BOOK_URL_MAP.put("墨子", "367k");
        BOOK_URL_MAP.put("诗经", "285n");
        BOOK_URL_MAP.put("楚辞", "308n");
        BOOK_URL_MAP.put("汉书", "183t");
        BOOK_URL_MAP.put("后汉书", "212t");
        BOOK_URL_MAP.put("三国志", "237t");
        BOOK_URL_MAP.put("资治通鉴", "304t");
        BOOK_URL_MAP.put("战国策", "200t");
        BOOK_URL_MAP.put("国语", "197t");
        BOOK_URL_MAP.put("礼记", "336k");
        BOOK_URL_MAP.put("尚书", "325k");
        BOOK_URL_MAP.put("春秋左传", "328k");
        BOOK_URL_MAP.put("世说新语", "505j");
        BOOK_URL_MAP.put("梦溪笔谈", "707j");
        BOOK_URL_MAP.put("天工开物", "734j");
        BOOK_URL_MAP.put("本草纲目", "1006g");
        BOOK_URL_MAP.put("黄帝内经", "1005g");
        BOOK_URL_MAP.put("九章算术", "601a");
        BOOK_URL_MAP.put("水经注", "604s");
        BOOK_URL_MAP.put("徐霞客游记", "751x");
        BOOK_URL_MAP.put("淮南子", "377k");
        BOOK_URL_MAP.put("列子", "374k");
        BOOK_URL_MAP.put("晏子春秋", "364k");
        BOOK_URL_MAP.put("搜神记", "499s");
        BOOK_URL_MAP.put("山海经", "487k");
        BOOK_URL_MAP.put("西厢记", "567x");
        BOOK_URL_MAP.put("牡丹亭", "570m");
        BOOK_URL_MAP.put("窦娥冤", "566d");
        BOOK_URL_MAP.put("桃花扇", "571t");
        BOOK_URL_MAP.put("长生殿", "569c");
        BOOK_URL_MAP.put("封神演义", "1051f");
        BOOK_URL_MAP.put("东周列国志", "1048d");
        BOOK_URL_MAP.put("隋唐演义", "1055s");
        BOOK_URL_MAP.put("三侠五义", "1058x");
        BOOK_URL_MAP.put("官场现形记", "1067g");
        BOOK_URL_MAP.put("老残游记", "1069l");
        BOOK_URL_MAP.put("二十年目睹之怪现状", "1068e");
        BOOK_URL_MAP.put("孽海花", "1070n");
        BOOK_URL_MAP.put("喻世明言", "1056y");
        BOOK_URL_MAP.put("警世通言", "1057j");
        BOOK_URL_MAP.put("醒世恒言", "1059x");
        BOOK_URL_MAP.put("初刻拍案惊奇", "1060c");
        BOOK_URL_MAP.put("二刻拍案惊奇", "1061p");
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(15000);
        RestTemplate rt = new RestTemplate(factory);
        return rt;
    }

    private String fetchHtml(String url) {
        try {
            RestTemplate rt = createRestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
            headers.set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            headers.set("Cache-Control", "max-age=0");
            headers.set("Connection", "keep-alive");
            headers.set("Referer", BASE_URL + "/");
            headers.set("Upgrade-Insecure-Requests", "1");
            org.springframework.http.HttpEntity<String> entity = new org.springframework.http.HttpEntity<>(headers);
            org.springframework.http.ResponseEntity<String> response = rt.exchange(
                url, org.springframework.http.HttpMethod.GET, entity, String.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("获取页面失败: {} - {}", url, e.getMessage());
            return null;
        }
    }

    public String getBookUrlByTitle(String title) {
        String id = BOOK_URL_MAP.get(title);
        if (id != null) {
            return BASE_URL + "/a/" + id + "/";
        }
        return null;
    }

    public boolean hasBook(String title) {
        return BOOK_URL_MAP.containsKey(title);
    }

    @Override
    public List<Book> searchBooks(String keyword, String sourceType, int page, int size) {
        List<Book> result = new ArrayList<>();
        try {
            for (Map.Entry<String, String> entry : BOOK_URL_MAP.entrySet()) {
                if (entry.getKey().contains(keyword) || keyword.contains(entry.getKey())) {
                    Book book = createBookTemplate(entry.getKey(), entry.getValue());
                    result.add(book);
                }
            }
            if (result.isEmpty()) {
                try {
                    String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
                    String searchUrl = BASE_URL + "/search/?keyword=" + encodedKeyword;
                    String html = fetchHtml(searchUrl);
                    if (html != null) {
                        Document doc = Jsoup.parse(html);
                        Elements links = doc.select("a[href^=/a/]");
                        for (Element link : links) {
                            String href = link.attr("href");
                            String text = link.text().trim();
                            if (href.matches("/a/\\w+/") && text.length() > 1 && text.length() < 30) {
                                String bookId = href.replace("/a/", "").replace("/", "");
                                Book book = createBookTemplate(text, bookId);
                                boolean exists = false;
                                for (Book b : result) {
                                    if (b.getTitle().equals(text)) {
                                        exists = true;
                                        break;
                                    }
                                }
                                if (!exists && result.size() < 20) {
                                    result.add(book);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("国学大师搜索失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("搜索国学大师书籍失败: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public Book getBookDetail(String bookId, String sourceType) {
        try {
            String url;
            String title;
            if (BOOK_URL_MAP.containsKey(bookId)) {
                title = bookId;
                url = BASE_URL + "/a/" + BOOK_URL_MAP.get(bookId) + "/";
            } else if (bookId.startsWith("http")) {
                url = bookId;
                title = extractTitleFromUrl(url);
            } else {
                url = BASE_URL + "/a/" + bookId + "/";
                title = bookId;
            }

            String html = fetchHtml(url);
            if (html == null) return null;

            Document doc = Jsoup.parse(html);

            Element cateDiv = doc.selectFirst(".info_cate");
            if (cateDiv == null && !doc.title().contains("《")) {
                log.warn("页面可能被重定向，未找到有效书籍内容: {}", url);
                return null;
            }

            String pageTitle = doc.title();
            String bookTitle = pageTitle.replaceAll("_.*$", "").replace("《", "").replace("》", "").trim();

            Book book = new Book();
            book.setTitle(bookTitle);
            book.setSourceUrl(url);
            book.setSourceType(SOURCE_TYPE);
            book.setPublicDomain(true);
            book.setStatus(1);
            book.setChapterCount(0);
            book.setWordCount(0);
            book.setCategory("古典文学");

            Element h1 = doc.selectFirst("h1");
            if (h1 != null) {
                String h1Text = h1.text().trim();
                if (!h1Text.isEmpty()) {
                    book.setTitle(h1Text.replace("《", "").replace("》", "").trim());
                }
            }

            String author = "佚名";
            Elements infoLinks = doc.select(".info a");
            for (Element link : infoLinks) {
                String text = link.text().trim();
                if (text.contains(")") && (text.contains("明") || text.contains("清") || text.contains("宋")
                    || text.contains("唐") || text.contains("元") || text.contains("汉") || text.contains("先秦"))) {
                    author = text;
                    break;
                }
            }
            book.setAuthor(author);

            book.setIntro("来自国学大师的公版书籍");

            return book;
        } catch (Exception e) {
            log.error("获取国学大师书籍详情失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Book importBook(String bookId, String sourceType) {
        String url;
        String title;

        if (BOOK_URL_MAP.containsKey(bookId)) {
            title = bookId;
            url = BASE_URL + "/a/" + BOOK_URL_MAP.get(bookId) + "/";
        } else if (bookId.startsWith("http")) {
            url = bookId;
            title = extractTitleFromUrl(url);
        } else {
            url = BASE_URL + "/a/" + bookId + "/";
            title = bookId;
        }

        Book existing = bookRepository.findBySourceUrl(url);
        if (existing != null) {
            throw new RuntimeException("该书已导入过");
        }

        Book onlineBook = getBookDetail(bookId, sourceType);
        if (onlineBook == null) {
            onlineBook = new Book();
            onlineBook.setTitle(title);
            onlineBook.setAuthor("佚名");
            onlineBook.setCategory("古典文学");
            onlineBook.setIntro("来自国学大师的公版书籍");
        }

        Book book = new Book();
        book.setTitle(onlineBook.getTitle());
        book.setAuthor(onlineBook.getAuthor());
        book.setCategory(onlineBook.getCategory());
        book.setIntro(onlineBook.getIntro());
        book.setCover(onlineBook.getCover());
        book.setSourceType(SOURCE_TYPE);
        book.setSourceId(System.currentTimeMillis());
        book.setSourceUrl(url);
        book.setPublicDomain(true);
        book.setStatus(1);
        book.setChapterCount(0);
        book.setWordCount(0);
        book.setViewCount(0L);
        book.setLikeCount(0L);

        Book savedBook = bookRepository.save(book);

        try {
            importBookContent(savedBook.getId(), url);
        } catch (Exception e) {
            log.warn("导入书籍内容失败: {}", e.getMessage());
        }

        Book resultBook = bookRepository.findById(savedBook.getId()).orElse(savedBook);
        if (resultBook.getChapterCount() == null || resultBook.getChapterCount() == 0) {
            bookRepository.deleteById(savedBook.getId());
            throw new RuntimeException("导入失败：未获取到任何章节内容");
        }

        return resultBook;
    }

    private void importBookContent(Long bookId, String bookUrl) {
        try {
            List<String[]> chapterList = getChapterList(bookUrl);
            if (chapterList.isEmpty()) {
                log.warn("未获取到章节列表: {}", bookUrl);
                return;
            }

            int chapterCount = 0;
            int totalWordCount = 0;
            int sortOrder = 1;

            for (String[] chapterInfo : chapterList) {
                String chapterTitle = chapterInfo[0];
                String chapterUrl = chapterInfo[1];

                try {
                    String content = getChapterContent(chapterUrl);
                    if (content != null && content.length() > 50) {
                        Chapter chapter = new Chapter();
                        chapter.setBookId(bookId);
                        chapter.setTitle(chapterTitle);
                        chapter.setSortOrder(sortOrder++);
                        chapter.setContent(content);
                        chapter.setWordCount(content.length());
                        chapterRepository.save(chapter);
                        chapterCount++;
                        totalWordCount += content.length();
                    }
                } catch (Exception e) {
                    log.warn("获取章节内容失败: {} - {}", chapterTitle, e.getMessage());
                }

                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (chapterCount > 0) {
                Book book = bookRepository.findById(bookId).orElse(null);
                if (book != null) {
                    book.setChapterCount(chapterCount);
                    book.setWordCount(totalWordCount);
                    bookRepository.save(book);
                    log.info("从国学大师导入成功，共{}章，{}字", chapterCount, totalWordCount);
                }
            }
        } catch (Exception e) {
            log.warn("从国学大师导入内容失败: {}", e.getMessage());
        }
    }

    private List<String[]> getChapterList(String bookUrl) {
        List<String[]> chapters = new ArrayList<>();
        try {
            String html = fetchHtml(bookUrl);
            if (html == null) {
                log.warn("章节列表页面为空: {}", bookUrl);
                return chapters;
            }

            Document doc = Jsoup.parse(html);

            Element contentDiv = doc.selectFirst(".info_cate");
            if (contentDiv == null) {
                contentDiv = doc.selectFirst(".info_content");
            }
            if (contentDiv == null) {
                contentDiv = doc.selectFirst(".info.l");
            }
            if (contentDiv == null) {
                contentDiv = doc.selectFirst("body");
                log.warn("未找到章节容器，使用body兜底: {}", bookUrl);
            }

            if (contentDiv != null) {
                Elements links = contentDiv.select("a[href]");
                log.debug("找到 {} 个链接", links.size());

                for (Element link : links) {
                    String href = link.attr("href");
                    String title = link.text().trim();

                    if (title.isEmpty() || title.length() > 100) continue;

                    String cleanTitle = title.replaceAll("^[\\s　]+", "").trim();
                    boolean isChapter = (cleanTitle.startsWith("第")
                        && (cleanTitle.contains("回") || cleanTitle.contains("章")
                            || cleanTitle.contains("卷") || cleanTitle.contains("篇")));

                    if (!isChapter) {
                        if (href.matches(".*/\\w+\\.html$") && title.length() > 4 && title.length() < 60
                            && (title.contains("第") || title.matches(".*\\d+.*"))) {
                            isChapter = true;
                        }
                    }

                    if (!isChapter) continue;

                    if (!href.endsWith(".html")) continue;

                    String fullUrl = href.startsWith("http") ? href
                        : href.startsWith("/") ? BASE_URL + href
                        : bookUrl + href;

                    boolean exists = false;
                    for (String[] c : chapters) {
                        if (c[0].equals(title)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        chapters.add(new String[]{cleanTitle, fullUrl});
                    }
                }
            }

            log.info("从 {} 获取到 {} 个章节", bookUrl, chapters.size());
        } catch (Exception e) {
            log.error("获取章节列表失败: {} - {}", bookUrl, e.getMessage());
        }
        return chapters;
    }

    private String getChapterContent(String chapterUrl) {
        try {
            String html = fetchHtml(chapterUrl);
            if (html == null) return null;

            Document doc = Jsoup.parse(html);
            StringBuilder content = new StringBuilder();

            Element contentDiv = doc.selectFirst("#infozj_txt.info_txt");
            if (contentDiv == null) {
                contentDiv = doc.selectFirst(".info_content.zj");
            }
            if (contentDiv == null) {
                contentDiv = doc.selectFirst(".info_txt");
            }

            if (contentDiv != null) {
                Elements paragraphs = contentDiv.select("p");
                if (paragraphs.size() > 0) {
                    for (Element p : paragraphs) {
                        String text = p.text().trim();
                        if (!text.isEmpty()) {
                            content.append(text).append("\n\n");
                        }
                    }
                } else {
                    content.append(contentDiv.text().trim());
                }
            }

            if (content.length() == 0) {
                Element body = doc.selectFirst("body");
                if (body != null) {
                    content.append(body.text());
                }
            }

            String result = content.toString().trim();
            if (result.length() > 500) {
                int footerIdx = result.indexOf("【 下载本文 】");
                if (footerIdx > 0) {
                    result = result.substring(0, footerIdx).trim();
                }
                footerIdx = result.indexOf("书中全文检索");
                if (footerIdx > 0) {
                    result = result.substring(0, footerIdx).trim();
                }
            }

            return result;
        } catch (Exception e) {
            log.warn("获取章节内容失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<String[]> getChapterList(String sourceBookId, String sourceType) {
        try {
            String bookUrl = sourceBookId;
            if (bookUrl == null || bookUrl.isEmpty()) {
                bookUrl = BASE_URL + "/a/" + BOOK_URL_MAP.getOrDefault(sourceBookId, sourceBookId) + "/";
            }
            return getChapterList(bookUrl);
        } catch (Exception e) {
            log.warn("获取章节列表失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public String getChapterContent(String sourceBookId, String chapterUrl, String sourceType) {
        return getChapterContent(chapterUrl);
    }

    private Book createBookTemplate(String title, String bookId) {
        Book book = new Book();
        book.setTitle(title);
        book.setAuthor("佚名");
        book.setCategory("古典文学");
        book.setIntro("来自国学大师的公版书籍");
        book.setSourceType(SOURCE_TYPE);
        book.setSourceUrl(BASE_URL + "/a/" + bookId + "/");
        book.setPublicDomain(true);
        book.setStatus(1);
        book.setChapterCount(0);
        book.setWordCount(0);
        return book;
    }

    private String extractTitleFromUrl(String url) {
        try {
            String path = url.replace(BASE_URL + "/a/", "").replace("/", "");
            for (Map.Entry<String, String> entry : BOOK_URL_MAP.entrySet()) {
                if (entry.getValue().equals(path)) {
                    return entry.getKey();
                }
            }
            return path;
        } catch (Exception e) {
        }
        return "未知书籍";
    }

    @Override
    public String getSourceName(String sourceType) {
        return SOURCE_NAME;
    }
}
