use std::ffi::{CStr, CString};
use mawjic_core::{iptv_parse_m3u, iptv_parse_xmltv, iptv_string_free};

#[test]
fn m3u_fixture_is_parsed_through_the_public_abi() {
    let input = CString::new(include_str!("fixtures/sample.m3u")).unwrap();
    let output = unsafe { iptv_parse_m3u(input.as_ptr()) };
    assert!(!output.is_null());
    let json = unsafe { CStr::from_ptr(output) }.to_str().unwrap().to_owned();
    unsafe { iptv_string_free(output) };
    assert!(json.contains("\"name\":\"News One\""));
    assert!(json.contains("\"name\":\"Sports One\""));
    assert!(json.contains("https://streams.example.invalid/news-one/index.m3u8"));
}

#[test]
fn xmltv_fixture_exposes_channel_and_programme_artwork() {
    let input = CString::new(include_str!("fixtures/sample.xml")).unwrap();
    let output = unsafe { iptv_parse_xmltv(input.as_ptr()) };
    assert!(!output.is_null());
    let json = unsafe { CStr::from_ptr(output) }.to_str().unwrap().to_owned();
    unsafe { iptv_string_free(output) };
    assert!(json.contains("Morning headlines"));
    assert!(json.contains("news.one"));
    assert!(json.contains("https://images.example.invalid/morning.jpg"));
}
