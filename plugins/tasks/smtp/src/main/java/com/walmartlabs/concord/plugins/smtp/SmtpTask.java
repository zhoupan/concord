package com.walmartlabs.concord.plugins.smtp;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.walmartlabs.concord.sdk.*;
import org.apache.commons.mail.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import java.io.FileReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Named("smtp")
public class SmtpTask implements Task {

    private static final Logger log = LoggerFactory.getLogger(SmtpTask.class);

    @Override
    public void execute(Context ctx) throws Exception {
        Map<String, Object> smtp = getCfg(ctx, "smtpParams", "smtp");
        Map<String, Object> mail = getCfg(ctx, "mailParams", "mail");
        send(ctx, smtp, mail);
    }

    @Deprecated
    public void call(@InjectVariable("context") Context ctx, Map<String, Object> smtpParams, Map<String, Object> mailParams) throws Exception {
        send(ctx, smtpParams, mailParams);
    }

    @Deprecated
    public void call(Map<String, Object> smtpParams, Map<String, Object> mailParams) throws Exception {
        send(null, smtpParams, mailParams);
    }

    @Deprecated
    public void send(String hostName, int port, String from, String to, String subject, String message, String bcc) throws Exception {
        Map<String, Object> smtp = new HashMap<>();
        smtp.put("host", hostName);
        smtp.put("port", port);

        Map<String, Object> mail = new HashMap<>();
        mail.put("from", from);
        mail.put("to", to);
        mail.put("subject", subject);
        mail.put("message", message);
        mail.put("bcc", bcc);

        send(null, smtp, mail);
    }

    private void send(Context ctx, Map<String, Object> smtp, Map<String, Object> mail) throws Exception {
        String host = assertString(smtp, "host", "smtp");
        int port = assertInt(smtp, "port", "smtp");

        mail = applyTemplate(ctx, mail);

        String from = assertString(mail, "from", "mail");
        Collection<String> to = oneOrManyStrings(mail, "to", "mail");
        Collection<String> cc = zeroOrManyStrings(mail, "cc", "mail");
        Collection<String> bcc = zeroOrManyStrings(mail, "bcc", "mail");
        Collection<String> replyTo = zeroOrManyStrings(mail, "replyTo", "mail");
        String subject = (String) mail.get("subject");

        try {
            Email email = createEmail(ctx, mail);

            email.setHostName(host);
            email.setSmtpPort(port);

            email.setFrom(from);

            for (String s : to) {
                email.addTo(s);
            }

            for (String s : cc) {
                email.addCc(s);
            }

            for (String s : bcc) {
                email.addBcc(s);
            }

            for (String s : replyTo) {
                email.addReplyTo(s);
            }

            email.setSubject(subject);

            String msgId = email.send();
            log.info("send [{}, {}] -> done, msgId: {}", smtp, mail, msgId);
        } catch (Exception e) {
            log.error("send [{}, {}] -> error", smtp, mail, e);
            throw e;
        }
    }

    private static Email createEmail(Context ctx, Map<String, Object> mail) throws EmailException {
        boolean isHtml = isHtml(mail);
        String msg = assertString(mail, "message", "mail");
        List<EmailAttachment> attachments = parseAttachments(ctx, mail);

        if (isHtml) {
            HtmlEmail email = new HtmlEmail();
            email.setHtmlMsg(msg);
            if (attachments != null) {
                processAttachments(email, attachments);
            }
            return email;
        } else {
            Email email;
            if (attachments != null) {
                MultiPartEmail mpe = new MultiPartEmail();
                processAttachments(mpe, attachments);
                email = mpe;
            } else {
                email = new SimpleEmail();
            }
            email.setMsg(msg);
            return email;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<EmailAttachment> parseAttachments(Context ctx, Map<String, Object> mail) {
        Object v = mail.get("attachments");
        if (v == null) {
            return null;
        }

        if (!(v instanceof Collection)) {
            throw new IllegalArgumentException("invalid 'attachments' type - expected a list of attachment values, got: " + v.getClass());
        }

        Path workDir = ContextUtils.getWorkDir(ctx);
        List<EmailAttachment> result = new ArrayList<>();
        for (Object o : (Collection) v) {
            EmailAttachment a = new EmailAttachment();
            if (o instanceof String) {
                a.setPath(assertPath(workDir, (String) o));
            } else if (o instanceof Map) {
                Map<String, Object> params = (Map<String, Object>) o;
                a.setPath(assertPath(workDir, MapUtils.assertString(params, "path")));
                a.setName(MapUtils.getString(params, "name"));
                a.setDescription(MapUtils.getString(params, "description"));
                a.setDisposition(parseDisposition(MapUtils.getString(params, "disposition")));
            } else {
                throw new IllegalArgumentException("invalid 'attachments' item type - expected a string or map, got: " + v.getClass());
            }
            result.add(a);
        }
        return result;
    }

    private static String assertPath(Path workDir, String path) {
        Path result = workDir.resolve(path).normalize().toAbsolutePath();
        if (!result.startsWith(workDir)) {
            throw new IllegalArgumentException("invalid attachment path: " + path);
        }

        if (!Files.exists(result)) {
            throw new IllegalArgumentException("attachment not found: " + result);
        }

        return result.toString();
    }

    private static String parseDisposition(String disposition) {
        if (disposition == null) {
            return EmailAttachment.ATTACHMENT;
        }

        if (EmailAttachment.ATTACHMENT.equals(disposition)) {
            return EmailAttachment.ATTACHMENT;
        } else if (EmailAttachment.INLINE.equals(disposition)) {
            return EmailAttachment.INLINE;
        }

        throw new IllegalArgumentException("invalid 'attachment' disposition value: '" + disposition + "', expected: " + EmailAttachment.ATTACHMENT + " or " + EmailAttachment.INLINE);
    }

    private static void processAttachments(MultiPartEmail email, List<EmailAttachment> attachments) throws EmailException {
        for (EmailAttachment a : attachments) {
            email.attach(a);
        }
    }

    private static Map<String, Object> applyTemplate(Context ctx, Map<String, Object> mailParams) throws Exception {
        if (mailParams == null) {
            return null;
        }

        String template = getTemplateName(mailParams);
        if (template == null) {
            return mailParams;
        }

        StringWriter out = new StringWriter();

        Path baseDir = getWorkDir(ctx);
        try (FileReader in = new FileReader(baseDir.resolve(template).toFile())) {
            MustacheFactory mf = new DefaultMustacheFactory();
            Mustache mustache = mf.compile(in, template);

            Object scope = getScope(ctx, mailParams);
            mustache.execute(out, scope);
        }

        Map<String, Object> m = new HashMap<>(mailParams);
        m.put("message", out.toString());
        return m;
    }

    private static Path getWorkDir(Context ctx) {
        if (ctx != null) {
            return ContextUtils.getWorkDir(ctx);
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    private static boolean isHtml(Map<String, Object> mailParams) {
        String template = (String) mailParams.get("template");
        if (template == null) {
            return false;
        }
        return template.trim().endsWith(".html");
    }

    private static Object getScope(Context ctx, Map<String, Object> mailParams) {
        Map<String, Object> templateParams = getTemplateParams(mailParams);
        Map<String, Object> ctxParams = ctx != null ? ctx.toMap() : Collections.emptyMap();
        Map<String, Object> result = new HashMap<>();
        result.putAll(ctxParams);
        result.putAll(templateParams);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getTemplateParams(Map<String, Object> mailParams) {
        Object templateParam = mailParams.get("template");
        if (templateParam instanceof Map) {
            Map<String, Object> params = (Map<String, Object>) ((Map) templateParam).get("params");
            return params != null ? params : Collections.emptyMap();
        }
        return Collections.emptyMap();
    }

    private static String getTemplateName(Map<String, Object> mailParams) {
        Object templateParam = mailParams.get("template");
        if (templateParam instanceof String) {
            return (String) templateParam;
        } else if (templateParam instanceof Map) {
            return (String) ((Map) templateParam).get("name");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getCfg(Context ctx, String a, String b) {
        Map<String, Object> m = (Map<String, Object>) ctx.getVariable(a);
        if (m == null) {
            m = (Map<String, Object>) ctx.getVariable(b);
        }
        return m;
    }

    private static String varName(String parent, String k) {
        if (parent == null) {
            return k;
        }
        return parent + "." + k;
    }

    private static String assertString(Map<String, Object> m, String k, String parent) {
        String v = m != null ? (String) m.get(k) : null;
        if (v == null) {
            throw new IllegalArgumentException("'" + varName(parent, k) + "' is required");
        }

        return v;
    }

    private static int assertInt(Map<String, Object> m, String k, String parent) {
        Integer v = (Integer) m.get(k);
        if (v == null) {
            throw new IllegalArgumentException("'" + varName(parent, k) + "' is required");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static Collection<String> zeroOrManyStrings(Map<String, Object> m, String k, String parent) {
        Object v = m.get(k);

        if (v instanceof String) {
            String s = (String) v;
            return Arrays.stream(s.split(","))
                    .map(String::trim)
                    .collect(Collectors.toList());
        }

        if (v instanceof Collection) {
            Collection<?> c = (Collection<?>) v;
            c.forEach(i -> {
                if (!(i instanceof String)) {
                    throw new IllegalArgumentException("'" + varName(parent, k) + "' - expected a list of string values, got: " + v);
                }
            });
            return (Collection<String>) c;
        }

        return Collections.emptyList();
    }

    private static Collection<String> oneOrManyStrings(Map<String, Object> m, String k, String parent) {
        Collection<String> c = zeroOrManyStrings(m, k, parent);
        if (c.isEmpty()) {
            throw new IllegalArgumentException("'" + varName(parent, k) + "' - expected a single string value or a list of strings");
        }
        return c;
    }
}
