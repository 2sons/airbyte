#
# Copyright (c) 2023 Airbyte, Inc., all rights reserved.
#


import sys
import traceback
from datetime import datetime

from source_s3.v4 import Config, Cursor, SourceS3, SourceS3StreamReader

from airbyte_cdk.entrypoint import AirbyteEntrypoint, launch
from airbyte_cdk.models import AirbyteErrorTraceMessage, AirbyteMessage, AirbyteTraceMessage, TraceType, Type


def get_source(args: list[str]):
    catalog_path = AirbyteEntrypoint.extract_catalog(args)
    try:
        return SourceS3(SourceS3StreamReader(), Config, catalog_path, cursor_cls=Cursor)
    except Exception:
        print(
            AirbyteMessage(
                type=Type.TRACE,
                trace=AirbyteTraceMessage(
                    type=TraceType.ERROR,
                    emitted_at=int(datetime.now().timestamp() * 1000),
                    error=AirbyteErrorTraceMessage(
                        message="Error starting the sync. This could be due to an invalid configuration or catalog. Please contact Support for assistance.",
                        stack_trace=traceback.format_exc(),
                    ),
                ),
            ).json(),
        )
        return None


if __name__ == "__main__":
    _args = sys.argv[1:]
    source = get_source(_args)
    if source:
        launch(source, _args)
