const std = @import("std");
const cli = @import("cli.zig");

pub fn main(init: std.process.Init) void {
    const exit_code = execute(init);
    if (exit_code != 0) std.process.exit(exit_code);
}

fn fail(io: std.Io, code: []const u8, status: ?u16, exit_code: u8) u8 {
    var buffer: [128]u8 = undefined;
    const bytes = if (status) |value|
        std.fmt.bufPrint(&buffer, "{{\"error\":\"{s}\",\"status\":{d}}}\n", .{ code, value }) catch return exit_code
    else
        std.fmt.bufPrint(&buffer, "{{\"error\":\"{s}\"}}\n", .{code}) catch return exit_code;
    std.Io.File.stderr().writeStreamingAll(io, bytes) catch {};
    return exit_code;
}

fn execute(init: std.process.Init) u8 {
    const allocator = init.gpa;
    const io = init.io;
    const args = init.minimal.args.toSlice(init.arena.allocator()) catch return fail(io, "USAGE_ERROR", null, 1);
    const command = cli.parse(allocator, args[1..]) catch return fail(io, "USAGE_ERROR", null, 1);
    defer allocator.free(command.path);
    const base = init.environ_map.get("MULINO_API_URL") orelse return fail(io, "CONFIG_ERROR", null, 1);
    cli.validateBase(base) catch return fail(io, "CONFIG_ERROR", null, 1);
    const token = init.environ_map.get("MULINO_TOKEN") orelse return fail(io, "CONFIG_ERROR", null, 1);
    if (!cli.validHeader(token, 16384, false)) return fail(io, "CONFIG_ERROR", null, 1);
    const timeout_ms = std.fmt.parseInt(u32, init.environ_map.get("MULINO_API_TIMEOUT_MS") orelse "10000", 10) catch return fail(io, "CONFIG_ERROR", null, 1);
    if (timeout_ms == 0 or timeout_ms > 60000) return fail(io, "CONFIG_ERROR", null, 1);
    const response_buffer = allocator.alloc(u8, 1048576) catch return fail(io, "TRANSPORT_ERROR", null, 2);
    defer allocator.free(response_buffer);

    var pending: [2]Event = undefined;
    var select = std.Io.Select(Event).init(io, &pending);
    defer select.cancelDiscard();
    select.concurrent(.request, request, .{ io, allocator, base, token, command, response_buffer }) catch return fail(io, "TRANSPORT_ERROR", null, 2);
    select.concurrent(.timeout, deadline, .{ io, timeout_ms }) catch return fail(io, "TRANSPORT_ERROR", null, 2);
    const event = select.await() catch return fail(io, "TRANSPORT_ERROR", null, 2);
    switch (event) {
        .timeout => return fail(io, "API_TIMEOUT", null, 2),
        .request => |response_or_error| {
            const response = response_or_error catch return fail(io, "TRANSPORT_ERROR", null, 2);
            if (response.status < 200 or response.status >= 300) return fail(io, "API_ERROR", response.status, 2);
            const bytes = response_buffer[0..response.length];
            cli.validateResponse(allocator, bytes, token) catch return fail(io, "INVALID_RESPONSE", null, 2);
            // The parsed document is only validated, never reserialized or numerically coerced.
            std.Io.File.stdout().writeStreamingAll(io, bytes) catch return fail(io, "OUTPUT_ERROR", null, 2);
            std.Io.File.stdout().writeStreamingAll(io, "\n") catch return fail(io, "OUTPUT_ERROR", null, 2);
            return 0;
        },
    }
}

const Response = struct { status: u16, length: usize };
const Event = union(enum) { request: anyerror!Response, timeout: std.Io.Cancelable!void };

fn deadline(io: std.Io, milliseconds: u32) std.Io.Cancelable!void {
    try io.sleep(.fromMilliseconds(milliseconds), .awake);
}

fn request(io: std.Io, allocator: std.mem.Allocator, base: []const u8, token: []const u8, command: cli.Command, response_buffer: []u8) anyerror!Response {
    const url = try std.fmt.allocPrint(allocator, "{s}{s}", .{ std.mem.trimEnd(u8, base, "/"), command.path });
    defer allocator.free(url);
    const authorization = try std.fmt.allocPrint(allocator, "Bearer {s}", .{token});
    defer allocator.free(authorization);
    var client: std.http.Client = .{ .allocator = allocator, .io = io };
    defer client.deinit();
    var writer: std.Io.Writer = .fixed(response_buffer);
    const extra_headers: []const std.http.Header = if (command.request_key) |key| &.{
        .{ .name = "Idempotency-Key", .value = key },
    } else &.{};
    var req = try client.request(command.method, try std.Uri.parse(url), .{
        .redirect_behavior = .unhandled,
        .keep_alive = false,
        .headers = .{ .authorization = .{ .override = authorization }, .content_type = .{ .override = "application/json" }, .accept_encoding = .{ .override = "identity" } },
        .extra_headers = extra_headers,
    });
    defer {
        // One request per process: close instead of draining a stalled/error response during teardown.
        if (req.connection) |connection| connection.closing = true;
        req.deinit();
    }
    if (command.body) |payload| {
        req.transfer_encoding = .{ .content_length = payload.len };
        var body = try req.sendBodyUnflushed(&.{});
        try body.writer.writeAll(payload);
        try body.end();
        try req.connection.?.flush();
    } else try req.sendBodiless();
    var response = try req.receiveHead(&.{});
    const status = @intFromEnum(response.head.status);
    if (status < 200 or status >= 300) return .{ .status = status, .length = 0 };
    const decompress_buffer: []u8 = switch (response.head.content_encoding) {
        .identity => &.{},
        .gzip, .deflate => try allocator.alloc(u8, std.compress.flate.max_window_len),
        .zstd => try allocator.alloc(u8, std.compress.zstd.default_window_len),
        .compress => return error.UnsupportedCompressionMethod,
    };
    defer if (decompress_buffer.len > 0) allocator.free(decompress_buffer);
    var transfer_buffer: [64]u8 = undefined;
    var decompress: std.http.Decompress = undefined;
    const reader = response.readerDecompressing(&transfer_buffer, &decompress, decompress_buffer);
    _ = reader.streamRemaining(&writer) catch |err| switch (err) {
        // Zig 0.16.0 fetch unwraps this optional error even when I/O cancellation left it null.
        error.ReadFailed => return response.bodyErr() orelse error.ReadFailed,
        else => return err,
    };
    return .{ .status = status, .length = writer.buffered().len };
}
