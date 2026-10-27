//! Mawjic's small, dependency-free parsing core.
//!
//! The C ABI returns newly allocated JSON strings. The caller must release each
//! non-null result with `iptv_string_free`. Input is UTF-8 and NUL-terminated.
use std::collections::HashMap;
use std::ffi::{c_char, CStr, CString};


const MAX_INPUT: usize = 64 * 1024 * 1024;
const MAX_CHANNELS: usize = 100_000;
const MAX_PROGRAMMES: usize = 1_000_000;
const MAX_XML_EVENTS: usize = 2_000_000;

#[derive(Debug, Clone, PartialEq, Eq)]
struct Channel {
    id: String,
    name: String,
    url: String,
    group: String,
    logo: String,
}

fn json_string(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if c < '\u{20}' => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn error_json(message: &str) -> String {
    format!("{{\"error\":{}}}", json_string(message))
}

fn find_unquoted_comma(s: &str) -> Option<usize> {
    let mut quoted = false;
    let mut escaped = false;
    for (i, c) in s.char_indices() {
        if escaped { escaped = false; continue; }
        if c == '\\' && quoted { escaped = true; continue; }
        if c == '"' { quoted = !quoted; }
        else if c == ',' && !quoted { return Some(i); }
    }
    None
}

fn attributes(s: &str) -> HashMap<String, String> {
    let bytes = s.as_bytes();
    let mut out = HashMap::new();
    let mut i = 0;
    while i < bytes.len() {
        while i < bytes.len() && (bytes[i].is_ascii_whitespace() || bytes[i] == b',') { i += 1; }
        let start = i;
        while i < bytes.len() && (bytes[i].is_ascii_alphanumeric() || matches!(bytes[i], b'-' | b'_' | b':')) { i += 1; }
        if start == i {
            let width = s.get(i..).and_then(|tail| tail.chars().next()).map(char::len_utf8).unwrap_or(1);
            i += width;
            continue;
        }
        let key = s[start..i].to_ascii_lowercase();
        while i < bytes.len() && bytes[i].is_ascii_whitespace() { i += 1; }
        if i >= bytes.len() || bytes[i] != b'=' { continue; }
        i += 1;
        while i < bytes.len() && bytes[i].is_ascii_whitespace() { i += 1; }
        if i >= bytes.len() { break; }
        let value = if bytes[i] == b'"' || bytes[i] == b'\'' {
            let quote = bytes[i]; i += 1; let start_value = i;
            while i < bytes.len() && bytes[i] != quote { i += 1; }
            let value = s[start_value..i].to_owned();
            if i < bytes.len() { i += 1; }
            value
        } else {
            let start_value = i;
            while i < bytes.len() && !bytes[i].is_ascii_whitespace() { i += 1; }
            s[start_value..i].trim_end_matches(',').to_owned()
        };
        out.insert(key, value);
    }
    out
}

fn parse_m3u(input: &str) -> Result<Vec<Channel>, String> {
    if input.len() > MAX_INPUT { return Err("Playlist exceeds the 64 MiB safety limit".into()); }
    let mut result = Vec::new();
    let mut pending: Option<(HashMap<String, String>, String)> = None;
    let mut pending_group = String::new();
    for raw in input.lines() {
        let line = raw.trim().trim_start_matches('\u{feff}').trim();
        if line.is_empty() { continue; }
        if let Some(rest) = line.strip_prefix("#EXTGRP:") {
            pending_group = rest.trim().to_owned();
            continue;
        }
        if let Some(rest) = line.strip_prefix("#EXTINF:") {
            let comma = find_unquoted_comma(rest).unwrap_or(rest.len());
            let attrs = attributes(&rest[..comma]);
            let title = if comma < rest.len() { rest[comma + 1..].trim().to_owned() } else { String::new() };
            pending = Some((attrs, title));
            continue;
        }
        if line.starts_with('#') { continue; }
        if let Some((attrs, title)) = pending.take() {
            if result.len() >= MAX_CHANNELS { return Err("Playlist exceeds the 100,000-channel safety limit".into()); }
            let name = attrs.get("tvg-name").filter(|s| !s.trim().is_empty()).cloned()
                .or(if title.is_empty() { None } else { Some(title) })
                .unwrap_or_else(|| line.to_owned());
            let id = attrs.get("tvg-id").filter(|s| !s.trim().is_empty()).cloned().unwrap_or_else(|| line.to_owned());
            let group = attrs.get("group-title").filter(|s| !s.trim().is_empty()).cloned().unwrap_or_else(|| pending_group.clone());
            let logo = attrs.get("tvg-logo").cloned().unwrap_or_default();
            result.push(Channel { id, name, url: line.to_owned(), group, logo });
            pending_group.clear();
        }
    }
    if result.is_empty() { return Err("No channels found; expected #EXTINF records followed by stream URLs".into()); }
    Ok(result)
}

fn channels_json(channels: &[Channel]) -> String {
    let items = channels.iter().map(|c| format!(
        "{{\"id\":{},\"name\":{},\"url\":{},\"group\":{},\"logo\":{}}}",
        json_string(&c.id), json_string(&c.name), json_string(&c.url), json_string(&c.group), json_string(&c.logo)
    )).collect::<Vec<_>>().join(",");
    format!("[{items}]")
}

#[derive(Debug, Default)]
struct XmlChannel { id: String, name: String, logo: String }
#[derive(Debug, Default)]
struct Programme { channel: String, title: String, description: String, image: String, start: i64, end: i64 }
#[derive(Debug)]
enum XmlEvent { Start(String, HashMap<String, String>, bool), End(String), Text(String) }

fn decode_xml(s: &str) -> Result<String, String> {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(pos) = rest.find('&') {
        out.push_str(&rest[..pos]);
        let tail = &rest[pos + 1..];
        let end = tail.find(';').ok_or_else(|| "Unterminated XML entity".to_string())?;
        let entity = &tail[..end];
        match entity {
            "amp" => out.push('&'), "lt" => out.push('<'), "gt" => out.push('>'),
            "quot" => out.push('"'), "apos" => out.push('\''),
            e if e.starts_with("#x") || e.starts_with("#X") => {
                let n = u32::from_str_radix(&e[2..], 16).map_err(|_| "Invalid hexadecimal XML entity")?;
                out.push(char::from_u32(n).ok_or_else(|| "Invalid Unicode XML entity".to_string())?);
            }
            e if e.starts_with('#') => {
                let n = e[1..].parse::<u32>().map_err(|_| "Invalid decimal XML entity")?;
                out.push(char::from_u32(n).ok_or_else(|| "Invalid Unicode XML entity".to_string())?);
            }
            _ => return Err(format!("Unsupported XML entity &{entity}; (external entities are not resolved)")),
        }
        rest = &tail[end + 1..];
    }
    out.push_str(rest);
    Ok(out)
}

fn xml_events(s: &str) -> Result<Vec<XmlEvent>, String> {
    let mut events = Vec::new();
    let mut i = 0;
    while i < s.len() {
        let rest = &s[i..];
        let lt = match rest.find('<') {
            Some(p) => p,
            None => { if !rest.trim().is_empty() { events.push(XmlEvent::Text(decode_xml(rest)?)); } break; }
        };
        if lt > 0 {
            let text = &rest[..lt];
            if !text.trim().is_empty() { events.push(XmlEvent::Text(decode_xml(text)?)); }
            i += lt;
            continue;
        }
        let after = &s[i..];
        if after.starts_with("<!--") {
            let end = after.find("-->").ok_or_else(|| "Unterminated XML comment".to_string())?;
            i += end + 3; continue;
        }
        if after.starts_with("<![CDATA[") {
            let end = after.find("]]>").ok_or_else(|| "Unterminated CDATA section".to_string())?;
            let content = &after[9..end];
            if !content.is_empty() { events.push(XmlEvent::Text(content.to_owned())); }
            i += end + 3; continue;
        }
        if after.starts_with("<?") {
            let end = after.find("?>").ok_or_else(|| "Unterminated XML processing instruction".to_string())?;
            i += end + 2; continue;
        }
        if after.as_bytes().get(..9).is_some_and(|prefix| prefix.eq_ignore_ascii_case(b"<!DOCTYPE")) || after.starts_with("<!ENTITY") {
            return Err("DTD/entity declarations are disabled for untrusted XMLTV input".into());
        }
        let end = after.find('>').ok_or_else(|| "Unterminated XML tag".to_string())?;
        let inside = after[1..end].trim();
        if let Some(name) = inside.strip_prefix('/') {
            events.push(XmlEvent::End(name.split_whitespace().next().unwrap_or("").to_ascii_lowercase()));
        } else if inside.starts_with('!') {
            return Err("Unsupported XML declaration".into());
        } else {
            let self_closing = inside.ends_with('/');
            let body = inside.trim_end_matches('/').trim();
            let split = body.find(char::is_whitespace).unwrap_or(body.len());
            let name = body[..split].to_ascii_lowercase();
            if name.is_empty() { return Err("Empty XML tag".into()); }
            let mut attrs = if split < body.len() { attributes(&body[split..]) } else { HashMap::new() };
            for value in attrs.values_mut() { *value = decode_xml(value)?; }
            events.push(XmlEvent::Start(name.clone(), attrs, self_closing));
            if self_closing { events.push(XmlEvent::End(name)); }
        }
        i += end + 1;
        if events.len() > MAX_XML_EVENTS { return Err("XMLTV has too many XML elements".into()); }
    }
    Ok(events)
}

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = y - if m <= 2 { 1 } else { 0 };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = m + if m > 2 { -3 } else { 9 };
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

fn xmltv_time(raw: &str) -> Result<i64, String> {
    let raw = raw.trim();
    let bytes = raw.as_bytes();
    if bytes.len() < 14 || !bytes[..14].iter().all(u8::is_ascii_digit) { return Err(format!("Invalid XMLTV timestamp: {raw}")); }
    let dt = raw.get(..14).ok_or_else(|| format!("Invalid XMLTV timestamp: {raw}"))?;
    let number = |a: usize, b: usize| dt[a..b].parse::<i64>().map_err(|_| format!("Invalid XMLTV timestamp: {raw}"));
    let (year, month, day, hour, minute, second) = (number(0,4)?, number(4,6)?, number(6,8)?, number(8,10)?, number(10,12)?, number(12,14)?);
    let leap_year = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let days_in_month = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if leap_year => 29,
        2 => 28,
        _ => 0,
    };
    if day < 1 || day > days_in_month || hour > 23 || minute > 59 || second > 60 {
        return Err(format!("Out-of-range XMLTV timestamp: {raw}"));
    }
    let zone = raw.get(14..).ok_or_else(|| format!("Invalid XMLTV timestamp: {raw}"))?.trim();
    let offset = if zone.is_empty() { 0 } else {
        let sign = match zone.as_bytes()[0] { b'+' => 1, b'-' => -1, _ => return Err(format!("Invalid XMLTV timezone: {raw}")) };
        let digits = zone.get(1..).ok_or_else(|| format!("Invalid XMLTV timezone: {raw}"))?.trim();
        if digits.len() != 4 || !digits.is_ascii() || !digits.bytes().all(|b| b.is_ascii_digit()) { return Err(format!("Invalid XMLTV timezone: {raw}")); }
        let hh = digits.get(..2).and_then(|v| v.parse::<i64>().ok()).ok_or_else(|| format!("Invalid XMLTV timezone: {raw}"))?;
        let mm = digits.get(2..).and_then(|v| v.parse::<i64>().ok()).ok_or_else(|| format!("Invalid XMLTV timezone: {raw}"))?;
        if hh > 23 || mm > 59 { return Err(format!("Invalid XMLTV timezone: {raw}")); }
        sign * (hh * 3600 + mm * 60)
    };
    Ok(days_from_civil(year, month, day) * 86400 + hour * 3600 + minute * 60 + second - offset)
}

fn parse_xmltv(input: &str) -> Result<String, String> {
    if input.len() > MAX_INPUT { return Err("XMLTV exceeds the 64 MiB safety limit".into()); }
    let events = xml_events(input)?;
    let mut channels: Vec<XmlChannel> = Vec::new();
    let mut programmes: Vec<Programme> = Vec::new();
    let mut channel: Option<XmlChannel> = None;
    let mut programme: Option<Programme> = None;
    let mut active_text: Option<(String, String)> = None;
    for event in events {
        match event {
            XmlEvent::Start(tag, attrs, self_closing) => {
                match tag.as_str() {
                    "channel" => channel = Some(XmlChannel { id: attrs.get("id").cloned().unwrap_or_default(), ..XmlChannel::default() }),
                    "programme" => {
                        if programmes.len() >= MAX_PROGRAMMES { return Err("XMLTV exceeds the 1,000,000-programme safety limit".into()); }
                        let start = xmltv_time(attrs.get("start").map(String::as_str).unwrap_or(""))?;
                        let end = xmltv_time(attrs.get("stop").or_else(|| attrs.get("end")).map(String::as_str).unwrap_or(""))?;
                        if end <= start { return Err("XMLTV programme end must be after start".into()); }
                        programme = Some(Programme { channel: attrs.get("channel").cloned().unwrap_or_default(), start, end, ..Programme::default() });
                    }
                    "icon" => if let Some(src) = attrs.get("src") {
                        if let Some(c) = channel.as_mut() { if c.logo.is_empty() { c.logo = src.clone(); } }
                        if let Some(p) = programme.as_mut() { if p.image.is_empty() { p.image = src.clone(); } }
                    },
                    "display-name" | "title" | "desc" => active_text = Some((tag, String::new())),
                    _ => {}
                }
                let _ = self_closing;
            }
            XmlEvent::Text(text) => if let Some((_, buffer)) = active_text.as_mut() { buffer.push_str(&text); },
            XmlEvent::End(tag) => {
                if let Some((active, value)) = active_text.take() {
                    if active != tag {
                        active_text = Some((active, value));
                    } else {
                        let value = value.trim().to_owned();
                        match active.as_str() {
                            "display-name" => if let Some(c) = channel.as_mut() { if c.name.is_empty() { c.name = value; } },
                            "title" => if let Some(p) = programme.as_mut() { if p.title.is_empty() { p.title = value; } },
                            "desc" => if let Some(p) = programme.as_mut() { if p.description.is_empty() { p.description = value; } },
                            _ => {}
                        }
                    }
                }
                if tag == "channel" { if let Some(c) = channel.take() { if !c.id.is_empty() { channels.push(c); } } }
                if tag == "programme" { if let Some(p) = programme.take() { if !p.channel.is_empty() && !p.title.is_empty() { programmes.push(p); } } }
            }
        }
    }
    let channel_json = channels.iter().map(|c| format!("{{\"id\":{},\"name\":{},\"logo\":{}}}", json_string(&c.id), json_string(&c.name), json_string(&c.logo))).collect::<Vec<_>>().join(",");
    let programme_json = programmes.iter().map(|p| format!("{{\"channel\":{},\"title\":{},\"description\":{},\"image\":{},\"start\":{},\"end\":{}}}", json_string(&p.channel), json_string(&p.title), json_string(&p.description), json_string(&p.image), p.start, p.end)).collect::<Vec<_>>().join(",");
    Ok(format!("{{\"channels\":[{channel_json}],\"programmes\":[{programme_json}]}}"))
}

unsafe fn input_string<'a>(input: *const c_char) -> Result<&'a str, String> {
    if input.is_null() { return Err("Input pointer was null".into()); }
    // The C ABI contract requires a valid NUL-terminated UTF-8 string.
    let bytes = unsafe { CStr::from_ptr(input) }.to_bytes();
    if bytes.len() > MAX_INPUT { return Err("Input exceeds the 64 MiB safety limit".into()); }
    std::str::from_utf8(bytes).map_err(|_| "Input is not valid UTF-8".to_string())
}

fn into_c_string(value: String) -> *mut c_char {
    CString::new(value).unwrap_or_else(|_| CString::new("{\"error\":\"output contained an invalid NUL byte\"}").expect("static JSON is valid")).into_raw()
}

/// Parse an M3U/M3U8 playlist into a JSON array of channel records.
/// Each returned pointer must be freed using `iptv_string_free`.
///
/// # Safety
/// `input` must be null or a valid NUL-terminated UTF-8 C string readable for its full length.
#[no_mangle]
pub unsafe extern "C" fn iptv_parse_m3u(input: *const c_char) -> *mut c_char {
    let result = std::panic::catch_unwind(|| {
        let input = unsafe { input_string(input) }?;
        parse_m3u(input).map(|channels| channels_json(&channels))
    }).unwrap_or_else(|_| Err("Parser terminated unexpectedly".into()));
    into_c_string(result.unwrap_or_else(|e| error_json(&e)))
}

/// Parse XMLTV into an object containing `channels` and `programmes` arrays.
///
/// # Safety
/// `input` must be null or a valid NUL-terminated UTF-8 C string readable for its full length.
#[no_mangle]
pub unsafe extern "C" fn iptv_parse_xmltv(input: *const c_char) -> *mut c_char {
    let result = std::panic::catch_unwind(|| {
        let input = unsafe { input_string(input) }?;
        parse_xmltv(input)
    }).unwrap_or_else(|_| Err("Parser terminated unexpectedly".into()));
    into_c_string(result.unwrap_or_else(|e| error_json(&e)))
}

/// Release a string returned by either parser. Null is accepted.
///
/// # Safety
/// `value` must be null or a pointer returned by this library's parser functions exactly once.
#[no_mangle]
pub unsafe extern "C" fn iptv_string_free(value: *mut c_char) {
    if !value.is_null() {
        // Only pointers returned by this library may be passed here.
        drop(unsafe { CString::from_raw(value) });
    }
}

/// Return the parser ABI version for diagnostic checks.
#[no_mangle]
pub extern "C" fn iptv_core_abi_version() -> u32 { 1 }

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_m3u_metadata_quotes_groups_and_duplicate_ids() {
        let input = "#EXTM3U\n#EXTINF:-1 tvg-id=\"news\" tvg-logo=\"https://img.test/a.png\" group-title=\"World, News\",News, HD\nhttps://tv.test/live.m3u8\n#EXTINF:-1 tvg-id=\"news\",Second\nhttps://tv.test/second\n";
        let parsed = parse_m3u(input).unwrap();
        assert_eq!(parsed.len(), 2);
        assert_eq!(parsed[0].id, "news");
        assert_eq!(parsed[0].name, "News, HD");
        assert_eq!(parsed[0].group, "World, News");
        assert_eq!(parsed[1].url, "https://tv.test/second");
        assert!(channels_json(&parsed).contains("https://img.test/a.png"));
    }

    #[test]
    fn rejects_empty_and_oversized_playlist() {
        assert!(parse_m3u("#EXTM3U\n").is_err());
        assert!(parse_m3u(&"x".repeat(MAX_INPUT + 1)).is_err());
    }

    #[test]
    fn xmltv_decodes_entities_and_reads_programme_artwork() {
        let xml = r#"<?xml version="1.0"?><tv><channel id="one"><display-name>News &amp; One</display-name><icon src="https://img.test/logo.png"/></channel><programme start="20240101010000 +0200" stop="20240101020000 +0200" channel="one"><title>Tom &amp; Jerry</title><desc>A &lt; B</desc><icon src="https://img.test/show.jpg"/></programme></tv>"#;
        let parsed = parse_xmltv(xml).unwrap();
        assert!(parsed.contains("News & One"));
        assert!(parsed.contains("Tom & Jerry"));
        assert!(parsed.contains("https://img.test/show.jpg"));
        assert!(parsed.contains("1704063600"));
    }

    #[test]
    fn timestamp_offsets_are_normalized_to_utc() {
        assert_eq!(xmltv_time("20240101010000 +0200").unwrap(), 1704063600);
        assert_eq!(xmltv_time("20231231230000 -0100").unwrap(), 1704067200);
        assert_eq!(xmltv_time("20240101000000").unwrap(), 1704067200);
        // The spring-forward local clock skips 02:00; explicit offsets remain continuous in UTC.
        let before = xmltv_time("20240331015900 +0100").unwrap();
        let after = xmltv_time("20240331030000 +0200").unwrap();
        assert_eq!(after - before, 60);
    }

    #[test]
    fn disables_external_entities_and_bad_timestamps() {
        assert!(parse_xmltv("<!DOCTYPE tv [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><tv/>").is_err());
        assert!(xmltv_time("20241301120000 +0000").is_err());
        assert!(decode_xml("&xxe;").is_err());
    }

    #[test]
    fn json_encoder_escapes_control_characters() {
        assert_eq!(json_string("a\n\"b"), "\"a\\n\\\"b\"");
    }
}
