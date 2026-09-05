const std = @import("std");
const Allocator = std.mem.Allocator;

pub const Command = struct { method: std.http.Method, path: []const u8, body: ?[]const u8, request_key: ?[]const u8 };
pub fn parse(allocator: Allocator, args: []const []const u8) !Command {
    if (args.len < 2) return error.InvalidArguments;
    const Route = enum { case_show, plan_show, plan_calculate, work_create, work_transition };
    const route: Route = if (eql(args[0], "case") and eql(args[1], "show")) .case_show else if (eql(args[0], "plan") and eql(args[1], "show")) .plan_show else if (eql(args[0], "plan") and eql(args[1], "calculate")) .plan_calculate else if (eql(args[0], "work") and eql(args[1], "create")) .work_create else if (eql(args[0], "work") and eql(args[1], "transition")) .work_transition else return error.InvalidArguments;
    const has_ref = route != .work_create;
    const writing = route != .case_show and route != .plan_show;
    const positional: usize = if (has_ref) 3 else 2;
    if (args.len < positional) return error.InvalidArguments;
    if (has_ref) {
        const ref = args[2];
        if (ref.len == 0 or ref.len > 256 or eql(ref, ".") or eql(ref, "..") or !std.unicode.utf8ValidateSlice(ref)) return error.InvalidArguments;
        for (ref) |byte| if (byte < 0x20 or byte == 0x7f) return error.InvalidArguments;
    }
    var body: ?[]const u8 = null;
    var key: ?[]const u8 = null;
    if (writing) {
        var index = positional;
        while (index < args.len) : (index += 2) {
            if (index + 1 >= args.len) return error.InvalidArguments;
            if (eql(args[index], "--json") and body == null) {
                body = args[index + 1];
            } else if (eql(args[index], "--request-key") and key == null) {
                key = args[index + 1];
            } else return error.InvalidArguments;
        }
        if (body == null or key == null or !validHeader(key.?, 200, true)) return error.InvalidArguments;
        if (body.?.len == 0 or body.?.len > 262144) return error.InvalidArguments;
        var parsed = std.json.parseFromSlice(std.json.Value, allocator, body.?, .{ .parse_numbers = false }) catch return error.InvalidArguments;
        defer parsed.deinit();
        if (parsed.value != .object) return error.InvalidArguments;
    } else if (args.len != positional) return error.InvalidArguments;
    const encoded = if (has_ref) try encodeRef(allocator, args[2]) else try allocator.dupe(u8, "");
    defer allocator.free(encoded);
    const path = switch (route) {
        .case_show => try std.fmt.allocPrint(allocator, "/agent/cases/{s}", .{encoded}),
        .plan_show => try std.fmt.allocPrint(allocator, "/agent/plans/{s}", .{encoded}),
        .plan_calculate => try std.fmt.allocPrint(allocator, "/cases/{s}/plans", .{encoded}),
        .work_create => try allocator.dupe(u8, "/agent/work-items"),
        .work_transition => try std.fmt.allocPrint(allocator, "/agent/work-items/{s}/transition", .{encoded}),
    };
    return .{ .method = if (writing) .POST else .GET, .path = path, .body = body, .request_key = key };
}

fn eql(a: []const u8, b: []const u8) bool {
    return std.mem.eql(u8, a, b);
}

fn encodeRef(allocator: Allocator, ref: []const u8) ![]u8 {
    const buffer = try allocator.alloc(u8, ref.len * 3);
    defer allocator.free(buffer);
    var count: usize = 0;
    const hex = "0123456789ABCDEF";
    for (ref) |byte| {
        if (std.ascii.isAlphanumeric(byte) or byte == '-' or byte == '_' or byte == '.' or byte == '~') {
            buffer[count] = byte;
            count += 1;
        } else {
            buffer[count..][0..3].* = .{ '%', hex[byte >> 4], hex[byte & 15] };
            count += 3;
        }
    }
    return allocator.dupe(u8, buffer[0..count]);
}

pub fn validHeader(value: []const u8, max: usize, allow_spaces: bool) bool {
    if (value.len == 0 or value.len > max or value[0] == ' ' or value[value.len - 1] == ' ') return false;
    for (value) |byte| if (byte < (if (allow_spaces) @as(u8, 0x20) else @as(u8, 0x21)) or byte > 0x7e) return false;
    return true;
}

pub fn validateBase(value: []const u8) !void {
    if (value.len == 0 or value.len > 4096) return error.InvalidConfiguration;
    for (value) |byte| if (byte <= 0x20 or byte >= 0x7f or byte == '\\') return error.InvalidConfiguration;
    const uri = std.Uri.parse(value) catch return error.InvalidConfiguration;
    if (uri.user != null or uri.password != null or uri.query != null or uri.fragment != null or uri.host == null or uri.port == 0) return error.InvalidConfiguration;
    var host_buffer: [std.Io.net.HostName.max_len]u8 = undefined;
    const host = uri.getHost(&host_buffer) catch return error.InvalidConfiguration;
    const local = std.ascii.eqlIgnoreCase(host.bytes, "localhost") or eql(host.bytes, "127.0.0.1") or eql(host.bytes, "[::1]") or eql(host.bytes, "::1") or std.ascii.eqlIgnoreCase(host.bytes, "host.docker.internal");
    if (!std.ascii.eqlIgnoreCase(uri.scheme, "https") and !(std.ascii.eqlIgnoreCase(uri.scheme, "http") and local)) return error.InvalidConfiguration;
    var path_buffer: [4096]u8 = undefined;
    const path = uri.path.toRaw(&path_buffer) catch return error.InvalidConfiguration;
    var segments = std.mem.splitScalar(u8, path, '/');
    while (segments.next()) |segment| if (eql(segment, ".") or eql(segment, "..")) return error.InvalidConfiguration;
}

pub fn validateResponse(allocator: Allocator, bytes: []const u8, token: []const u8) !void {
    if (bytes.len == 0 or bytes.len > 1048576) return error.InvalidResponse;
    var parsed = std.json.parseFromSlice(std.json.Value, allocator, bytes, .{ .parse_numbers = false }) catch return error.InvalidResponse;
    defer parsed.deinit();
    if (token.len > 0 and (std.mem.find(u8, bytes, token) != null or containsToken(parsed.value, token, 0))) return error.SensitiveResponse;
}

fn containsToken(value: std.json.Value, token: []const u8, depth: usize) bool {
    if (depth > 128) return true;
    switch (value) {
        .string => |text| return std.mem.find(u8, text, token) != null,
        .array => |items| {
            for (items.items) |item| if (containsToken(item, token, depth + 1)) return true;
        },
        .object => |object| {
            var entries = object.iterator();
            while (entries.next()) |entry| {
                if (std.mem.find(u8, entry.key_ptr.*, token) != null or containsToken(entry.value_ptr.*, token, depth + 1)) return true;
            }
        },
        else => {},
    }
    return false;
}

test "routes encode references as a single path component" {
    const allocator = std.testing.allocator;
    const command = try parse(allocator, &.{ "case", "show", "CASE/one?x#%한" });
    defer allocator.free(command.path);
    try std.testing.expectEqualStrings("/agent/cases/CASE%2Fone%3Fx%23%25%ED%95%9C", command.path);
    try std.testing.expectEqual(std.http.Method.GET, command.method);
    try std.testing.expectEqual(@as(?[]const u8, null), command.body);
}

test "writes require explicit stable keys and preserve exact body bytes" {
    const allocator = std.testing.allocator;
    const json = "{\"warehouseId\":9007199254740993,\"productIds\":[1],\"horizonDays\":30}";
    const command = try parse(allocator, &.{ "plan", "calculate", "CASE-1", "--request-key", "key-1", "--json", json });
    defer allocator.free(command.path);
    try std.testing.expectEqualStrings("/cases/CASE-1/plans", command.path);
    try std.testing.expectEqual(std.http.Method.POST, command.method);
    try std.testing.expectEqualStrings(json, command.body.?);
    try std.testing.expectEqualStrings("key-1", command.request_key.?);
}

test "supported work routes do not create unavailable material or PO operations" {
    const allocator = std.testing.allocator;
    const examples = [_]struct { args: []const []const u8, path: []const u8 }{
        .{ .args = &.{ "plan", "show", "PLAN-1" }, .path = "/agent/plans/PLAN-1" },
        .{ .args = &.{ "work", "create", "--json", "{}", "--request-key", "k" }, .path = "/agent/work-items" },
        .{ .args = &.{ "work", "transition", "WI-1", "--json", "{}", "--request-key", "k" }, .path = "/agent/work-items/WI-1/transition" },
    };
    for (examples) |example| {
        const command = try parse(allocator, example.args);
        defer allocator.free(command.path);
        try std.testing.expectEqualStrings(example.path, command.path);
    }
    try std.testing.expectError(error.InvalidArguments, parse(allocator, &.{ "material", "show", "1" }));
    try std.testing.expectError(error.InvalidArguments, parse(allocator, &.{ "po", "propose", "1" }));
}

test "missing duplicate or malformed arguments fail before making a request" {
    const examples = [_][]const []const u8{
        &.{},                                                               &.{ "case", "show" },                                         &.{ "case", "show", "" },                                                     &.{ "case", "show", ".." },
        &.{ "case", "show", "CASE-1", "--json", "{}" },                     &.{ "work", "create", "--json", "{}" },                       &.{ "work", "create", "--json", "{}", "--request-key", "" },                  &.{ "work", "create", "--json", "{}", "--request-key", "bad\r\nkey" },
        &.{ "work", "create", "--json", "not-json", "--request-key", "k" }, &.{ "work", "create", "--json", "[]", "--request-key", "k" }, &.{ "work", "create", "--json", "{}", "--json", "{}", "--request-key", "k" },
    };
    for (examples) |args| try std.testing.expectError(error.InvalidArguments, parse(std.testing.allocator, args));
}

test "credential destinations reject plaintext remote hosts and ambiguous URLs" {
    for ([_][]const u8{ "https://erp.example/api/v1", "http://127.0.0.1:8080/api/v1", "http://localhost:8080/api/v1", "http://host.docker.internal:8080/api/v1" }) |value| try validateBase(value);
    for ([_][]const u8{ "not-url", "http://public.example/api/v1", "https://name:secret@erp.example/api/v1", "https://erp.example/api/v1?x=y", "https://erp.example/api/v1#part", "file:///tmp/api", "https://erp.example/api/../admin" }) |value| try std.testing.expectError(error.InvalidConfiguration, validateBase(value));
}

test "responses keep decimal source bytes but reject malformed or reflected credentials" {
    const exact = "{\"quantity\":999999999999.999999,\"id\":9007199254740993}";
    try validateResponse(std.testing.allocator, exact, "private-token");
    try std.testing.expectError(error.InvalidResponse, validateResponse(std.testing.allocator, "not-json", "private-token"));
    try std.testing.expectError(error.SensitiveResponse, validateResponse(std.testing.allocator, "{\"error\":\"private-token\"}", "private-token"));
    try std.testing.expectError(error.SensitiveResponse, validateResponse(std.testing.allocator, "{\"nested\":[\"private-\\u0074oken\"]}", "private-token"));
}
