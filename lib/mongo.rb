# encoding: UTF-8

$:.unshift(File.join(File.dirname(__FILE__), '..', 'lib'))

module Mongo
  VERSION = "1.0.2"
end

module Mongo
  ASCENDING  =  1
  DESCENDING = -1
  GEO2D      = '2d'

  module Constants
    OP_REPLY        = 1
    OP_MSG          = 1000
    OP_UPDATE       = 2001
    OP_INSERT       = 2002
    OP_QUERY        = 2004
    OP_GET_MORE     = 2005
    OP_DELETE       = 2006
    OP_KILL_CURSORS = 2007

    OP_QUERY_TAILABLE          = 2
    OP_QUERY_SLAVE_OK          = 4
    OP_QUERY_NO_CURSOR_TIMEOUT = 16
  end

end

require 'bson'

require 'mongo/util/conversions'
require 'mongo/util/support'
require 'mongo/util/core_ext'
require 'mongo/util/server_version'

require 'mongo/collection'
require 'mongo/connection'
require 'mongo/cursor'
require 'mongo/db'
require 'mongo/exceptions'
require 'mongo/gridfs/grid_ext'
require 'mongo/gridfs/grid'
require 'mongo/gridfs/grid_io'
require 'mongo/gridfs/grid_file_system'
