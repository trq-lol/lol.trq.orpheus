// SPDX-License-Identifier: MIT

// A stand-in media player for tests. It publishes to the macOS Now Playing centre, takes its script from stdin
// and prints every command the system delivers to stdout, one per line.
//
// stdin, tab separated:
//   track <title> <artist> <album> <album artist> <duration ms> <cover RRGGBB or -> <music|video>
//   state <playing|paused|stopped>
//   position <ms>
//   controls <on|off>      (off disables next and previous)
//   hold <on|off>          (on keeps changes back, off publishes them in one set, like a player would)
//   quit
// stdout: "ready", then "done <verb>" after each script line and "command <name> [value]" for each command.

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>

static void say(NSString *line) {
    printf("%s\n", line.UTF8String);
    fflush(stdout);
}

static NSMutableDictionary *info;
static BOOL playing = NO;
static BOOL held = NO;

// The position at anchorTime, so that every update carries the real current position.
static double anchorPosition = 0;
static double anchorTime = 0;

static double now(void) {
    return [NSDate date].timeIntervalSince1970;
}

static double currentPosition(void) {
    return anchorPosition + (playing ? now() - anchorTime : 0);
}

static void setPosition(double seconds) {
    anchorPosition = seconds;
    anchorTime = now();
}

static void publish(void) {
    info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(currentPosition());
    info[MPNowPlayingInfoPropertyPlaybackRate] = @(playing ? 1.0 : 0.0);
    if (held) {
        return;
    }
    [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = info;
}

static void setState(MPNowPlayingPlaybackState state) {
    setPosition(currentPosition());
    playing = state == MPNowPlayingPlaybackStatePlaying;
    [MPNowPlayingInfoCenter defaultCenter].playbackState = state;
    publish();
}

static NSImage *flatImage(unsigned int rgb) {
    NSImage *image = [[NSImage alloc] initWithSize:NSMakeSize(64, 64)];
    [image lockFocus];
    [[NSColor colorWithSRGBRed:((rgb >> 16) & 0xFF) / 255.0
                         green:((rgb >> 8) & 0xFF) / 255.0
                          blue:(rgb & 0xFF) / 255.0
                         alpha:1.0] setFill];
    NSRectFill(NSMakeRect(0, 0, 64, 64));
    [image unlockFocus];
    return image;
}

static void handle(NSString *line) {
    NSArray<NSString *> *parts = [line componentsSeparatedByString:@"\t"];
    NSString *verb = parts[0];
    NSString * (^field)(NSUInteger) = ^NSString *(NSUInteger index) {
        return index < parts.count ? parts[index] : @"";
    };
    MPRemoteCommandCenter *commands = [MPRemoteCommandCenter sharedCommandCenter];
    if ([verb isEqualToString:@"track"]) {
        [info removeAllObjects];
        info[MPMediaItemPropertyTitle] = field(1);
        if (field(2).length > 0) {
            info[MPMediaItemPropertyArtist] = field(2);
        }
        if (field(3).length > 0) {
            info[MPMediaItemPropertyAlbumTitle] = field(3);
        }
        double millis = field(5).doubleValue;
        if (millis > 0) {
            info[MPMediaItemPropertyPlaybackDuration] = @(millis / 1000.0);
        }
        setPosition(0);
        BOOL video = [field(7) isEqualToString:@"video"];
        info[MPNowPlayingInfoPropertyMediaType] = @(video ? MPNowPlayingInfoMediaTypeVideo : MPNowPlayingInfoMediaTypeAudio);
        if (field(6).length == 6) {
            unsigned int rgb = 0;
            [[NSScanner scannerWithString:field(6)] scanHexInt:&rgb];
            NSImage *image = flatImage(rgb);
            info[MPMediaItemPropertyArtwork] = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size
                                                                                requestHandler:^NSImage *(CGSize size) {
                                                                                    return image;
                                                                                }];
        }
        publish();
    } else if ([verb isEqualToString:@"state"]) {
        NSString *state = field(1);
        setState([state isEqualToString:@"playing"]  ? MPNowPlayingPlaybackStatePlaying
                 : [state isEqualToString:@"paused"] ? MPNowPlayingPlaybackStatePaused
                                                     : MPNowPlayingPlaybackStateStopped);
    } else if ([verb isEqualToString:@"position"]) {
        setPosition(field(1).doubleValue / 1000.0);
        publish();
    } else if ([verb isEqualToString:@"controls"]) {
        BOOL all = [field(1) isEqualToString:@"on"];
        commands.nextTrackCommand.enabled = all;
        commands.previousTrackCommand.enabled = all;
    } else if ([verb isEqualToString:@"hold"]) {
        held = [field(1) isEqualToString:@"on"];
        if (!held) {
            publish();
        }
    } else if ([verb isEqualToString:@"quit"]) {
        exit(0);
    }
    say([NSString stringWithFormat:@"done %@", verb]);
}

int main(void) {
    @autoreleasepool {
        // Without this the process never checks in as an application, NSRunningApplication does not know it and
        // mediaremote-adapter's stream drops its updates.
        [NSApplication sharedApplication];
        [NSApp setActivationPolicy:NSApplicationActivationPolicyProhibited];
        info = [NSMutableDictionary dictionary];
        MPRemoteCommandCenter *commands = [MPRemoteCommandCenter sharedCommandCenter];
        [commands.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
            setState(MPNowPlayingPlaybackStatePlaying);
            say(@"command play");
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [commands.pauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
            setState(MPNowPlayingPlaybackStatePaused);
            say(@"command pause");
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [commands.togglePlayPauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
            setState(playing ? MPNowPlayingPlaybackStatePaused : MPNowPlayingPlaybackStatePlaying);
            say(@"command toggle");
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [commands.stopCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
            setState(MPNowPlayingPlaybackStateStopped);
            say(@"command stop");
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [commands.nextTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
            say(@"command next");
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [commands.previousTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
            say(@"command previous");
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [commands.changePlaybackPositionCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
            MPChangePlaybackPositionCommandEvent *seek = (MPChangePlaybackPositionCommandEvent *)event;
            setPosition(seek.positionTime);
            publish();
            say([NSString stringWithFormat:@"command seek %lld", (long long)llround(seek.positionTime * 1000.0)]);
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [commands.changeShuffleModeCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
            MPChangeShuffleModeCommandEvent *shuffle = (MPChangeShuffleModeCommandEvent *)event;
            commands.changeShuffleModeCommand.currentShuffleType = shuffle.shuffleType;
            say([NSString stringWithFormat:@"command shuffle %@", shuffle.shuffleType == MPShuffleTypeOff ? @"false" : @"true"]);
            return MPRemoteCommandHandlerStatusSuccess;
        }];
        [commands.changeRepeatModeCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
            MPChangeRepeatModeCommandEvent *repeat = (MPChangeRepeatModeCommandEvent *)event;
            commands.changeRepeatModeCommand.currentRepeatType = repeat.repeatType;
            say([NSString stringWithFormat:@"command repeat %ld", (long)repeat.repeatType]);
            return MPRemoteCommandHandlerStatusSuccess;
        }];

        dispatch_async(dispatch_get_global_queue(QOS_CLASS_UTILITY, 0), ^{
            NSFileHandle *input = [NSFileHandle fileHandleWithStandardInput];
            NSMutableData *pending = [NSMutableData data];
            while (YES) {
                NSData *chunk = [input availableData];
                if (chunk.length == 0) {
                    exit(0);
                }
                [pending appendData:chunk];
                while (YES) {
                    NSRange newline = [pending rangeOfData:[NSData dataWithBytes:"\n" length:1]
                                                   options:0
                                                     range:NSMakeRange(0, pending.length)];
                    if (newline.location == NSNotFound) {
                        break;
                    }
                    NSString *line = [[NSString alloc] initWithData:[pending subdataWithRange:NSMakeRange(0, newline.location)]
                                                           encoding:NSUTF8StringEncoding];
                    [pending replaceBytesInRange:NSMakeRange(0, newline.location + 1) withBytes:NULL length:0];
                    if (line.length > 0) {
                        dispatch_async(dispatch_get_main_queue(), ^{
                            handle(line);
                        });
                    }
                }
            }
        });

        say(@"ready");
        [[NSRunLoop mainRunLoop] run];
    }
    return 0;
}
